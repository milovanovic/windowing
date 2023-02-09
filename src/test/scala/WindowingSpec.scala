package windowing

import chisel3._
import chisel3.experimental._
import chisel3.util._
import java.io._

import chisel3.iotesters.Driver
import chisel3.iotesters.PeekPokeTester
import dspblocks.{AXI4DspBlock, AXI4StandaloneBlock}
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import breeze.math.Complex
import breeze.signal.{fourierTr, iFourierTr}
import breeze.linalg._
//import org.scalatest.{FlatSpec, Matchers}
import scala.math.{Pi, pow}

import chisel3.iotesters.{PeekPokeTester, Driver, ChiselFlatSpec}

// class MemTest(c: UsesMem) extends PeekPokeTester(c) {
//   poke(c.io.address, 1)
//   step(10)
//   poke(c.io.address, 2)
//   step(50)
//   poke(c.io.address, 8)
//   step(20)
// }
// 
// 
// class MemTester extends ChiselFlatSpec {
//   behavior of "MemTest"
//   backends foreach { backend => // only verilator tested
//     it should s"it should work for all backends" in {
//       Driver(() => new UsesMem(64, UInt(8.W)), "verilator")(c => new MemTest(c)) should be (true)
//     }
//   }
// }

class WindowingBlockTester
(
  dut: WindowingBlock[FixedPoint] with WindowingStandaloneBlock,
  address: AddressSet,
  ramAddress: Option[AddressSet],
  windowFuncRunTime: WindowFunctionType = WindowFunctionTypes.Blackman(),
  freq      : Double = 15.54/1024,
  tolerance : Int = 3,
  beatBytes : Int = 4,
  scale     : Int = 14,
  silentFail: Boolean = false
) extends PeekPokeTester(dut.module) with AXI4StreamModel with AXI4MasterModel with HasSignalUtils {
  def memAXI: AXI4Bundle = dut.ioMem.get

  val mod = dut.module
  val params = dut.params
  val fftSize = params.numPoints
  val master = bindMaster(dut.in.getWrappedValue) // bindMaster(dut.in)
  
   val binPoint = (params.protoIQ.real.cloneType match {
     case fp: FixedPoint => fp.binaryPoint.get
     case _ => 0
  })


  val windowSeqInit = params.windowFunc match {
    case WindowFunctionTypes.Hamming(_, alpha, beta, _) => WindowFunctions.hammingWindow(params.numPoints, alpha, beta)
    case WindowFunctionTypes.Hanning(_, _) => WindowFunctions.hanningWindow(params.numPoints)
    case WindowFunctionTypes.Blackman(_, a0, a1, a2, _) => WindowFunctions.blackmanWindow(params.numPoints, a0, a1, a2)
    case WindowFunctionTypes.Triangular(_, _) => WindowFunctions.triangularWindow(params.numPoints)
    case WindowFunctionTypes.User(_, userWindow) => {
      require(userWindow.length == params.numPoints, "Length of specified window function is not the same as fft size")
      userWindow
    }
    case WindowFunctionTypes.None(_) => Seq.fill(params.numPoints)(1.0)
  }
  
  // just drive input data - real data only
  //val inData = (0 until fftSize).map( i => (math.sin(2 * math.Pi * freq * i) * scala.math.pow(2, 14)).toInt)
  // complex data
  val inData = getTone(numSamples = fftSize, f1r = freq, f1i = freq).map(c => Complex(c.real*scala.math.pow(2, scale), c.imag*scala.math.pow(2, scale)))
  println("Input data is:")
  inData.map(c => println(c.toString))
  
  // ###############################################################################################################################################
  // ################################## Check windowing mode where init state of the windowing RAM is used #########################################
  // ################################## or when constant window function is used (exclude RAM for storing coefficents) #############################
  // ###############################################################################################################################################
  
  var expectedOut = inData.zip(windowSeqInit).map { case (sig, win) => (sig*win) }.map(c => Complex(c.real, c.imag))
  println("Expected output is:")
  expectedOut.map(c => println(c.toString))
  val expectedDepth = fftSize

  // val axi4StreamIn = formAXI4StreamRealData(inData, 16) //formAXI4StreamRealData(inData, 16)
  val axi4StreamIn = formAXI4StreamComplexData(inData, 16)
  step(1)
  poke(dut.out.ready, true.B)
  
  var outSeq = Seq[Int]()
  var peekedVal: BigInt = 0
  var realSeq = Seq[Int]()
  var imagSeq = Seq[Int]()
  var tmpReal: Short = 0
  var tmpImag: Short = 0

  if (params.constWindow == true) {
    // enable windowing
    memWriteWord(address.base + beatBytes, BigInt(1))
    master.addTransactions((0 until axi4StreamIn.size).map(i => AXI4StreamTransaction(data = axi4StreamIn(i))))
    master.addTransactions(axi4StreamIn.zipWithIndex.map { case (data, idx) => AXI4StreamTransaction(data = data,  last = if (idx == axi4StreamIn.length - 1) true else false) })
    // check only one fft window
    while (outSeq.length < fftSize) {
      if (peek(dut.out.valid) == 1 && peek(dut.out.ready) == 1) {
        peekedVal = peek(dut.out.bits.data)
        outSeq = outSeq :+ peekedVal.toInt
        tmpReal = (peekedVal.toInt / pow(2,16)).toShort
        tmpImag = (peekedVal.toInt - (tmpReal.toInt * pow(2,16))).toShort
        realSeq = realSeq :+ tmpReal.toInt
        imagSeq = imagSeq :+ tmpImag.toInt
      }
      step(1)
    }

    var receivedOut = realSeq.zip(imagSeq).map { case (real, imag) => Complex(real, imag) }
    println("Received output")
    receivedOut.map(c => println(c.toString))

    expectedOut.zip(receivedOut).foreach {
      case (in, out) =>
        require(math.abs(in.real - out.real) <= tolerance & math.abs(in.imag - out.imag) <= tolerance, "Tolerance is not satisfied")
    }
  // ###############################################################################################################################
  // ######################### Test streaming windowing when RAM is used for storing window coefficents ############################
  // ###############################################################################################################################
  }
  else {
    var cycle = 0
    // write window function to RAM
    // assume that fftSize is equal to compile time parameter numPoints
    
    val windowSeqRunTime = windowFuncRunTime match {
      case WindowFunctionTypes.Hamming(_, alpha, beta, _) => WindowFunctions.hammingWindow(params.numPoints, alpha, beta)
      case WindowFunctionTypes.Hanning(_, _) => WindowFunctions.hanningWindow(params.numPoints)
      case WindowFunctionTypes.Blackman(_, a0, a1, a2, _) => WindowFunctions.blackmanWindow(params.numPoints, a0, a1, a2)
      case WindowFunctionTypes.Triangular(_, _) => WindowFunctions.triangularWindow(params.numPoints)
      case WindowFunctionTypes.User(_, userWindow) => {
        require(userWindow.length == params.numPoints, "Length of specified window function is not the same as fft size")
        userWindow
      }
      case WindowFunctionTypes.None(_) => Seq.fill(params.numPoints)(1.0)
    }
    val windowHDW = windowSeqRunTime.map(c => BigDecimal(c * (1 << (params.protoWin.getWidth - 2))).toBigInt)
    while (cycle < fftSize) {
      memWriteWord(ramAddress.get.base + cycle*beatBytes, windowHDW(cycle))
      cycle += 1
    }
    step(500)
    memWriteWord(address.base + beatBytes, BigInt(1))

    master.addTransactions((0 until axi4StreamIn.size).map(i => AXI4StreamTransaction(data = axi4StreamIn(i))))
    master.addTransactions(axi4StreamIn.zipWithIndex.map { case (data, idx) => AXI4StreamTransaction(data = data,  last = if (idx == axi4StreamIn.length - 1) true else false) })
    
    outSeq = Seq()
    realSeq = Seq()
    imagSeq = Seq()
    
    // check only one fft window 
    while (outSeq.length < fftSize) {
      if (peek(dut.out.valid) == 1 && peek(dut.out.ready) == 1) {
        peekedVal = peek(dut.out.bits.data)
        outSeq = outSeq :+ peekedVal.toInt
        tmpReal = (peekedVal.toInt / pow(2,16)).toShort
        tmpImag = (peekedVal.toInt - (tmpReal.toInt * pow(2,16))).toShort
        realSeq = realSeq :+ tmpReal.toInt
        imagSeq = imagSeq :+ tmpImag.toInt
      }
      step(1)
    }
    expectedOut = inData.zip(windowSeqRunTime).map { case (sig, win) => (sig*win) }
    var receivedOut = realSeq.zip(imagSeq).map { case (real, imag) => Complex(real, imag) }

    println("Expected output is:")
    expectedOut.map(c => println(c.toString))
    
    println("Received output")
    receivedOut.map(c => println(c.toString))
    expectedOut.zip(receivedOut).foreach {
      case (in, out) =>
        require(math.abs(in.real - out.real) <= tolerance & math.abs(in.imag - out.imag) <= tolerance, "Tolerance is not satisfied")
    }
  }

  step(100)
  stepToCompletion(expectedDepth*5, silentFail = silentFail)
}

class WindowingBlockSpec extends AnyFlatSpec with Matchers {
  implicit val p: Parameters = Parameters.empty

  val paramsWindowingConst = WindowingParams.fixed(
    dataWidth = 16,
    numPoints = 16,
    binPoint = 0,
    numMulPipes = 1,
    constWindow = true,
    dirName = "test_run_dir",
    memoryFile = "./test_run_dir/triangularConst.hex",
    windowFunc = WindowFunctionTypes.Triangular(dataWidth_tmp = 16)//Blackman(dataWidth_tmp = 16)
  )

  it should "Test windowing block with parameter constWindow = true" in {
    val lazyDut = LazyModule(new WindowingBlock(address = AddressSet(0x010000, 0xFF), ramAddress = None, paramsWindowingConst, beatBytes = 4) with WindowingStandaloneBlock)

    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => lazyDut.module) {
      c => new WindowingBlockTester(lazyDut, address = AddressSet(0x010000, 0xFF), ramAddress = None, beatBytes = 4, scale = 14, silentFail = true)
    } should be (true)
  }
  
  val paramsWindowingRunTime = WindowingParams.fixed(
    dataWidth = 16,
    numPoints = 16,
    binPoint = 0,
    numMulPipes = 1,
    dirName = "test_run_dir",
    memoryFile = "./test_run_dir/TriangularRunTime.hex",
    windowFunc = WindowFunctionTypes.Triangular(dataWidth_tmp = 16)//Blackman(dataWidth_tmp = 16)
  )

  it should "Test windowing block with parameter constWindow = false" in {
    val lazyDut = LazyModule(new WindowingBlock(address = AddressSet(0x010000, 0xFF), ramAddress = Some(AddressSet(0x000000, 0xFFF)), paramsWindowingRunTime, beatBytes = 4) with WindowingStandaloneBlock)

    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => lazyDut.module) {
      c => new WindowingBlockTester(lazyDut, address = AddressSet(0x010000, 0xFF), ramAddress = Some(AddressSet(0x000000, 0xFFF)), WindowFunctionTypes.Blackman(), beatBytes = 4, scale = 14, silentFail = true)
    } should be (true)
  }

  val paramsWindowingRunTime_14 = WindowingParams.fixed(
    dataWidth = 16,
    numPoints = 32,
    binPoint = 14,
    numMulPipes = 1,
    dirName = "test_run_dir",
    //constWindow = ,
    memoryFile = "./test_run_dir/BlackmanRunTime.hex",
    windowFunc = WindowFunctionTypes.Blackman(dataWidth_tmp = 16)//Blackman(dataWidth_tmp = 16)
  )

  it should "Test windowing block with parameter constWindow = false and binaryPoint of input data is equal to 14" in {
    val lazyDut = LazyModule(new WindowingBlock(address = AddressSet(0x010000, 0xFF), ramAddress = Some(AddressSet(0x000000, 0xFFF)), paramsWindowingRunTime_14, beatBytes = 4) with WindowingStandaloneBlock)

    chisel3.iotesters.Driver.execute(Array("-tiwv", "-tbn", "verilator", "-tivsuv"), () => lazyDut.module) {
      c => new WindowingBlockTester(lazyDut, address = AddressSet(0x010000, 0xFF), ramAddress = Some(AddressSet(0x000000, 0xFFF)), WindowFunctionTypes.Blackman(), beatBytes = 4, scale = 14, silentFail = true)
    } should be (true)
  }
}
