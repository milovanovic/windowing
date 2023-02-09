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
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.regmapper._

import java.io._

trait WindowingMultipleInOutsStandaloneBlock extends WindowingBlockMultipleInOuts[FixedPoint]  {
  def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  val ioMem = windowing.mem.map { m => {
    val ioMemNode = BundleBridgeSource(() => AXI4Bundle(standaloneParams))

    m :=
      BundleBridgeToAXI4(AXI4MasterPortParameters(Seq(AXI4MasterParameters("bundleBridgeToAXI4")))) :=
      ioMemNode

    val ioMem = InModuleBody { ioMemNode.makeIO() }
    ioMem
  }}
  val numIns = 4
  val ins: Seq[ModuleValue[AXI4StreamBundle]] = for (i <- 0 until numIns) yield {
    implicit val valName = ValName(s"in_$i")
    val in = BundleBridgeSource[AXI4StreamBundle](() => AXI4StreamBundle(AXI4StreamBundleParameters(n = 4)))
    streamNode :=
      BundleBridgeToAXI4Stream(AXI4StreamMasterPortParameters(AXI4StreamMasterParameters(n = 4))) :=
      in
    InModuleBody { in.makeIO() }
  }
  val outs: Seq[ModuleValue[AXI4StreamBundle]] = for (o <- 0 until numIns) yield {
    implicit val valName = ValName(s"out_$o")
    val out = BundleBridgeSink[AXI4StreamBundle]()
    out :=
      AXI4StreamToBundleBridge(AXI4StreamSlavePortParameters(AXI4StreamSlaveParameters())) :=
      streamNode
    InModuleBody { out.makeIO() }
  }
}

class WindowingBlockMultipleInOuts [T <: Data : Real: BinaryRepresentation] (address: AddressSet, ramAddress: Option[AddressSet], val params: WindowingParams[T], beatBytes: Int)(implicit p: Parameters) extends LazyModule(){
  if (params.constWindow == false) require(ramAddress != None)

  val windowing = if (params.constWindow) LazyModule(new AXI4WindowingBlockROMMultipleInOuts(params, address, beatBytes)) else LazyModule(new AXI4WindowingBlockRAMMultipleInOuts(address, ramAddress.get, params, beatBytes))
  val streamNode = NodeHandle(windowing.streamNode, windowing.streamNode)

  lazy val module = new LazyModuleImp(this)
}

object WindowingBlockMultipleInOutsApp extends App
{
  val paramsWindowing = WindowingParams.fixed(
    dataWidth = 16,
    binPoint = 14,
    numMulPipes = 1,
    trimType = Convergent,
    constWindow = false,
    dirName = "test_run_dir",
    memoryFile = "./test_run_dir/blacman.txt",
    windowFunc = WindowFunctionTypes.Blackman(dataWidth_tmp = 16)
  )

  implicit val p: Parameters = Parameters.empty

  val testModule = LazyModule(new WindowingBlockMultipleInOuts(address = AddressSet(0x010000, 0xFF), ramAddress = Some(AddressSet(0x000000, 0x0FFF)), paramsWindowing, beatBytes = 4) with WindowingMultipleInOutsStandaloneBlock {
    override def standaloneParams = AXI4BundleParameters(addrBits = 32, dataBits = 32, idBits = 1)
  })
  (new ChiselStage).execute(args, Seq(ChiselGeneratorAnnotation(() => testModule.module)))

  //chisel3.Driver.execute(args, ()=> testModule.module)

}
