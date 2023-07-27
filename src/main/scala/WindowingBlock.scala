package windowing

import chisel3._
import chisel3.util._
//  not suppported for chisel version used in this project
//import chisel3.util.experimental.loadMemoryFromFileInline
import chisel3.experimental._
import chisel3.util.experimental.loadMemoryFromFile
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}

import dsptools._
import dsptools.numbers._

import dspblocks._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.amba.axi4stream._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._
import org.chipsalliance.cde.config.Parameters

import java.io._

trait WindowingStandaloneBlock extends WindowingBlock[FixedPoint]  {
  def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  val ioMem = windowing.mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

    m :=
      BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
      ioMemNode

    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}

  val ioInNode = BundleBridgeSource(() => new AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
  val ioOutNode = BundleBridgeSink[AXI4StreamBundle]()

  ioOutNode :=
    AXI4StreamToBundleBridge(AXI4StreamSlaveParameters()) :=
    streamNode :=
    BundleBridgeToAXI4Stream(AXI4StreamMasterParameters(n = 4)) :=
    ioInNode

  val in = InModuleBody { ioInNode.makeIO() }
  val out = InModuleBody { ioOutNode.makeIO() }
}

class WindowingBlock [T <: Data : Real: BinaryRepresentation] (address: AddressSet, ramAddress: Option[AddressSet], val params: WindowingParams[T], beatBytes: Int)(implicit p: Parameters) extends LazyModule(){
  if (params.constWindow == false) require(ramAddress != None)

  val windowing = if (params.constWindow) LazyModule(new AXI4WindowingBlockROM(params, address, beatBytes)) else LazyModule(new AXI4WindowingBlockRAM(address, ramAddress.get, params, beatBytes))
  val streamNode = NodeHandle(windowing.streamNode, windowing.streamNode)

  lazy val module = new LazyModuleImp(this)
}

object WindowingBlockApp extends App
{
  val paramsWindowing = WindowingParams.fixed(
    dataWidth = 16,
    binPoint = 14,
    numMulPipes = 1,
    trimType = Convergent,
    dirName = "test_run_dir",
    memoryFile = "./test_run_dir/blacman.txt",
    constWindow = true,
    windowFunc = WindowFunctionTypes.Blackman(dataWidth_tmp = 16)
  )
  implicit val p: Parameters = Parameters.empty

  /*val testModule = LazyModule(new WindowingBlock(address = AddressSet(0x010000, 0xFF), ramAddress = Some(AddressSet(0x000000, 0x0FFF)), paramsWindowing, beatBytes = 4) with WindowingStandaloneBlock {
    override def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  })*/
  val testModule = LazyModule(new WindowingBlock(address = AddressSet(0x010000, 0xFF), ramAddress = None, paramsWindowing, beatBytes = 4) with WindowingStandaloneBlock {
    override def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  })
  (new ChiselStage).execute(args, Seq(ChiselGeneratorAnnotation(() => testModule.module)))
}
