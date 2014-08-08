package referencechip

import Chisel._
import uncore._
import rocket._
import DRAMModel._
import DRAMModel.MemModelConstants._

class FPGAOuterMemorySystem extends Module {
  val io = new Bundle {
    val tiles = Vec.fill(params(NTiles)){new TileLinkIO}.flip
    val htif = (new TileLinkIO).flip
    val incoherent = Vec.fill(params(LNClients)){Bool()}.asInput
    val mem = new MemIO
  }

  val master = Module(new L2CoherenceAgent(0), params(L2HellaCacheParams))
  val net = Module(new ReferenceChipCrossbarNetwork)
  net.io.clients zip (io.tiles :+ io.htif) map { case (net, end) => net <> end }
  net.io.masters.head <> master.io.inner
  master.io.incoherent zip io.incoherent map { case (m, c) => m := c }

  val conv = Module(new MemIOUncachedTileLinkIOConverter(2))
  conv.io.uncached <> master.io.outer
  io.mem.req_cmd <> Queue(conv.io.mem.req_cmd, 2)
  io.mem.req_data <> Queue(conv.io.mem.req_data, params(TLDataBits)/params(MIFDataBits))
  conv.io.mem.resp <> Queue(io.mem.resp)
}

class FPGAUncore extends Module {
  val (htifw, nTiles) = (params(HTIFWidth),params(NTiles))
  val io = new Bundle {
    val host = new HostIO(htifw)
    val mem = new MemIO
    val tiles = Vec.fill(nTiles){new TileLinkIO}.flip
    val htif = Vec.fill(nTiles){new HTIFIO}.flip
    val incoherent = Vec.fill(nTiles){Bool()}.asInput
  }
  val htif = Module(new HTIF(CSRs.reset))
  val outmemsys = Module(new FPGAOuterMemorySystem)
  val incoherentWithHtif = (io.incoherent :+ Bool(true).asInput)
  outmemsys.io.incoherent := incoherentWithHtif
  htif.io.cpu <> io.htif
  outmemsys.io.mem <> io.mem

  // Add networking headers and endpoint queues
  (outmemsys.io.tiles :+ outmemsys.io.htif).zip(io.tiles :+ htif.io.mem).zipWithIndex.map { 
    case ((outer, client), i) => 
      outer.acquire <> Queue(TileLinkHeaderOverwriter(client.acquire, i, false))
      outer.release <> Queue(TileLinkHeaderOverwriter(client.release, i, false))
      outer.finish <> Queue(TileLinkHeaderOverwriter(client.finish, i, true))
      client.grant <> Queue(outer.grant, 1, pipe = true)
      client.probe <> Queue(outer.probe)
  }

  htif.io.host.out <> io.host.out
  htif.io.host.in <> io.host.in
}

class FPGATopIO extends TopIO

class FPGATop extends Module {
  /*
  val ntiles = 1
  val nmshrs = 2
  val htif_width = 16
  val co = new MESICoherence(new FullRepresentation(ntiles+1))
  implicit val ln = LogicalNetworkConfiguration(log2Up(ntiles)+1, 1, ntiles+1)
  implicit val as = AddressSpaceConfiguration(params[Int]("PADDR_BITS"), params[Int]("VADDR_BITS"), params[Int]("PGIDX_BITS"), params[Int]("ASID_BITS"), params[Int]("PERM_BITS"))
  implicit val tl = TileLinkConfiguration(co = co, ln = ln,
                                          addrBits = as.paddrBits-params[Int]("OFFSET_BITS"), 
                                          clientXactIdBits = log2Up(1+8), 
                                          masterXactIdBits = 2*log2Up(2*1+1), 
                                          dataBits = params[Int]("CACHE_DATA_SIZE_IN_BYTES")*8, 
                                          writeMaskBits = params[Int]("WRITE_MASK_BITS"), 
                                          wordAddrBits = params[Int]("SUBWORD_ADDR_BITS"), 
                                          atomicOpBits = params[Int]("ATOMIC_OP_BITS"))
  implicit val l2 = L2CoherenceAgentConfiguration(tl, 1, 8)
  implicit val mif = MemoryIFConfiguration(params[Int]("MEM_ADDR_BITS"), params[Int]("MEM_DATA_BITS"), params[Int]("MEM_TAG_BITS"), params[Int]("MEM_DATA_BEATS"))
  implicit val uc = FPGAUncoreConfiguration(l2, tl, mif, ntiles, nSCR = 64, offsetBits = params[Int]("OFFSET_BITS"))

  val ic = ICacheConfig(64, 1, ntlb = 4, tl = tl, as = as, btb = BTBConfig(as, 8, 2))
  val dc = DCacheConfig(64, 1, ntlb = 4, nmshr = 2, nrpq = 16, nsdq = 17, tl = tl, as = as, reqtagbits = -1, databits = -1)
  val rc = RocketConfiguration(tl, as, ic, dc,
                               fastMulDiv = false)
*/

  val nTiles = params(NTiles)
  val io = new FPGATopIO
 
  params.alter(params(TileLinkL1Params))

  val resetSigs = Vec.fill(nTiles){Bool()}
  val tileList = (0 until nTiles).map(r => Module(new Tile(resetSignal = resetSigs(r))))
  val uncore = Module(new FPGAUncore)

  for (i <- 0 until nTiles) {
    val hl = uncore.io.htif(i)
    val tl = uncore.io.tiles(i)
    val il = uncore.io.incoherent(i)

    resetSigs(i) := hl.reset
    val tile = tileList(i)

    tile.io.tilelink <> tl
    il := hl.reset
    tile.io.host.id := UInt(i)
    tile.io.host.reset := Reg(next=Reg(next=hl.reset))
    tile.io.host.pcr_req <> Queue(hl.pcr_req)
    hl.pcr_rep <> Queue(tile.io.host.pcr_rep)
    hl.ipi_req <> Queue(tile.io.host.ipi_req)
    tile.io.host.ipi_rep <> Queue(hl.ipi_rep)
  }
 
  uncore.io.host <> io.host
  uncore.io.mem <> io.mem
}

abstract class AXISlave extends Module {
  val aw = 5
  val dw = 32
  val io = new Bundle {
    val in = Decoupled(Bits(width = dw)).flip
    val out = Decoupled(Bits(width = dw))
    val addr = Bits(INPUT, aw)
  }
}

class Slave extends AXISlave
{
  val top = Module(new FPGATop)

  val memw = top.io.mem.resp.bits.data.getWidth
  val htifw = top.io.host.in.bits.getWidth
  
  val n = 4
  def wen(i: Int) = io.in.valid && io.addr(log2Up(n)-1,0) === UInt(i)
  def ren(i: Int) = io.out.ready && io.addr(log2Up(n)-1,0) === UInt(i)
  val rdata = Vec.fill(n){Bits(width = dw)}
  val rvalid = Vec.fill(n){Bool()}
  val wready = Vec.fill(n){Bool()}

  io.in.ready := wready(io.addr)
  io.out.valid := rvalid(io.addr)
  io.out.bits := rdata(io.addr)

  // write r0 -> htif.in (blocking)
  wready(0) := top.io.host.in.ready
  top.io.host.in.valid := wen(0)
  top.io.host.in.bits := io.in.bits

  // read cr0 -> htif.out (nonblocking)
  rdata(0) := Cat(top.io.host.out.bits, top.io.host.out.valid)
  rvalid(0) := Bool(true)
  top.io.host.out.ready := ren(0)
  require(dw >= htifw + 1)

  // read cr1 -> mem.req_cmd (nonblocking)
  // the memory system is FIFO from hereon out, so just remember the tags here
  val tagq = Module(new Queue(top.io.mem.req_cmd.bits.tag, 4))
  tagq.io.enq.bits := top.io.mem.req_cmd.bits.tag
  tagq.io.enq.valid := ren(1) && top.io.mem.req_cmd.valid && !top.io.mem.req_cmd.bits.rw
  top.io.mem.req_cmd.ready := ren(1)
  rdata(1) := Cat(top.io.mem.req_cmd.bits.addr, top.io.mem.req_cmd.bits.rw, top.io.mem.req_cmd.valid && (tagq.io.enq.ready || top.io.mem.req_cmd.bits.rw))
  rvalid(1) := Bool(true)
  require(dw >= top.io.mem.req_cmd.bits.addr.getWidth + 1 + 1)

  // write cr1 -> mem.resp (nonblocking)
  val in_count = Reg(init=UInt(0, log2Up(memw/dw)))
  val rf_count = Reg(init=UInt(0, log2Up(params[Int]("CACHE_DATA_SIZE_IN_BYTES")*8/memw)))
  require(memw % dw == 0 && isPow2(memw/dw))
  val in_reg = Reg(top.io.mem.resp.bits.data)
  top.io.mem.resp.bits.data := Cat(io.in.bits, in_reg(in_reg.getWidth-1,dw))
  top.io.mem.resp.bits.tag := tagq.io.deq.bits
  top.io.mem.resp.valid := wen(1) && in_count.andR
  tagq.io.deq.ready := top.io.mem.resp.fire() && rf_count.andR
  wready(1) := top.io.mem.resp.ready
  when (wen(1) && wready(1)) {
    in_count := in_count + UInt(1)
    in_reg := top.io.mem.resp.bits.data
  }
  when (top.io.mem.resp.fire()) {
    rf_count := rf_count + UInt(1)
  }

  // read cr2 -> mem.req_data (blocking)
  val out_count = Reg(init=UInt(0, log2Up(memw/dw)))
  top.io.mem.req_data.ready := ren(2) && out_count.andR
  rdata(2) := top.io.mem.req_data.bits.data >> (out_count * UInt(dw))
  rvalid(2) := top.io.mem.req_data.valid
  when (ren(2) && rvalid(2)) { out_count := out_count + UInt(1) }

  // read cr3 -> debug signals (nonblocking)
  rdata(3) := Cat(top.io.mem.req_cmd.valid, tagq.io.enq.ready)
  rvalid(3) := Bool(true)

  // writes to cr2, cr3 ignored
  wready(2) := Bool(true)
  wready(3) := Bool(true)
}
