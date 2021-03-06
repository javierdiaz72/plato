package io.iohk.ethereum.blockchain.sync

import akka.actor._
import io.iohk.ethereum.blockchain.sync.PeerRequestHandler.ResponseReceived
import io.iohk.ethereum.db.storage.AppStateStorage
import io.iohk.ethereum.domain._
import io.iohk.ethereum.ledger._
import io.iohk.ethereum.network.EtcPeerManagerActor.PeerInfo
import io.iohk.ethereum.network.Peer
import io.iohk.ethereum.network.PeerEventBusActor.PeerEvent.MessageFromPeer
import io.iohk.ethereum.network.PeerEventBusActor.SubscriptionClassifier.MessageClassifier
import io.iohk.ethereum.network.PeerEventBusActor.{PeerSelector, Subscribe}
import io.iohk.ethereum.network.p2p.messages.CommonMessages.NewBlock
import io.iohk.ethereum.network.p2p.messages.PV62._
import io.iohk.ethereum.ommers.OmmersPool.{AddOmmers, RemoveOmmers}
import io.iohk.ethereum.transactions.PendingTransactionsManager
import io.iohk.ethereum.transactions.PendingTransactionsManager.{AddTransactions, RemoveTransactions}
import io.iohk.ethereum.utils.Config.SyncConfig
import org.spongycastle.util.encoders.Hex

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

//TODO Refactor to get rid of most of mutable state [EC-320]
class RegularSync(
    val appStateStorage: AppStateStorage,
    val etcPeerManager: ActorRef,
    val peerEventBus: ActorRef,
    val ommersPool: ActorRef,
    val pendingTransactionsManager: ActorRef,
    val broadcaster: BlockBroadcast,
    val ledger: Ledger,
    val syncConfig: SyncConfig,
    val slotTimeConverter: SlotTimeConverter,
    implicit val scheduler: Scheduler)
  extends Actor with ActorLogging with PeerListSupport with BlacklistSupport {

  import RegularSync._
  import syncConfig._

  private var headersQueue: Seq[SignedBlockHeader] = Nil
  private var waitingForActor: Option[ActorRef] = None
  var resolvingBranches: Boolean = false
  var topOfTheChain: Boolean = false
  private var resumeRegularSyncTimeout: Option[Cancellable] = None

  scheduler.schedule(printStatusInterval, printStatusInterval, self, PrintStatus)

  peerEventBus ! Subscribe(MessageClassifier(Set(NewBlock.code, NewBlockHashes.code), PeerSelector.AllPeers))

  def handleCommonMessages: Receive = handlePeerListMessages orElse handleBlacklistMessages

  override def receive: Receive = idle

  def idle: Receive = handleCommonMessages orElse {
    case Start =>
      log.info("Starting block synchronization")
      appStateStorage.fastSyncDone()
      context become running
      askForHeaders()

    case StartIdle =>
      appStateStorage.fastSyncDone()
      context become running
  }

  def running: Receive = handleBasicMessages orElse handleAdditionalMessages orElse handleResumingAndPrinting

  def handleBasicMessages: Receive = handleCommonMessages orElse handleResponseToRequest

  def handleResumingAndPrinting: Receive = {
    case ResumeRegularSync =>
      resumeRegularSync()

    case PrintStatus =>
      val currentSlot = slotTimeConverter.getSlotNumberFromTime(System.currentTimeMillis().millis)
      log.info(s"Block: ${appStateStorage.getBestBlockNumber()}, slot: $currentSlot Peers: ${handshakedPeers.size} (${blacklistedPeers.size} blacklisted)")
  }

  def handleAdditionalMessages: Receive = handleNewBlockMessages orElse handleMinedBlock orElse handleNewBlockHashesMessages

  private def resumeRegularSync(): Unit = {
    cancelScheduledResume()

    // The case that waitingForActor is defined (we are waiting for some response),
    // can happen when we are on top of the chain and currently handling newBlockHashes message

    if (waitingForActor.isEmpty)
      askForHeaders()
    else
      resumeRegularSyncTimeout = Some(scheduler.scheduleOnce(checkForNewBlockInterval, self, ResumeRegularSync))
  }

  private def cancelScheduledResume(): Unit = {
    resumeRegularSyncTimeout.foreach(_.cancel)
    resumeRegularSyncTimeout = None
  }

  def handleNewBlockMessages: Receive = {
    case MessageFromPeer(NewBlock(newBlock, _), peerId) =>
      //we allow inclusion of new block only if we are not syncing
      if (notDownloading() && topOfTheChain) {
        val importResult = ledger.importBlock(newBlock)

        importResult match {
          case BlockImportedToTop(newBlocks, newTds) =>
            broadcastBlocks(newBlocks, newTds)
            updateTxAndOmmerPools(newBlocks, Nil)
            log.debug(s"Added new block ${newBlock.signedHeader.header.number} to the top of the chain received from $peerId")

          case BlockEnqueued =>
            ommersPool ! AddOmmers(newBlock.signedHeader)
            log.debug(s"Block ${newBlock.signedHeader.header.number} (${Hex.toHexString(newBlock.signedHeader.hash.toArray)}) from $peerId " +
            s"added to queue")

          case DuplicateBlock =>
            log.debug(s"Ignoring duplicate block ${newBlock.signedHeader.header.number} (${Hex.toHexString(newBlock.signedHeader.hash.toArray)}) from $peerId")

          case UnknownParent =>
            // This is normal when receiving broadcasted blocks
            log.debug(s"Ignoring orphaned block ${newBlock.signedHeader.header.number} (${Hex.toHexString(newBlock.signedHeader.hash.toArray)}) from $peerId")

          case ChainReorganised(oldBranch, newBranch, totalDifficulties) =>
            updateTxAndOmmerPools(newBranch, oldBranch)
            broadcastBlocks(newBranch, totalDifficulties)
            log.debug(s"Imported block ${newBlock.signedHeader.header.number} (${Hex.toHexString(newBlock.signedHeader.hash.toArray)}) from $peerId, " +
              s"resulting in chain reorganisation: new branch of length ${newBranch.size} with head at block " +
              s"${newBranch.last.signedHeader.header.number} (${Hex.toHexString(newBranch.last.signedHeader.hash.toArray)})")

          case BlockImportFailed(error) =>
            blacklist(peerId, blacklistDuration, error)
        }
      }
  }

  /**
    * Handles NewHashesMessege, should only cover this message when we are top of the chain
    */
  def handleNewBlockHashesMessages: Receive = {
    case MessageFromPeer(NewBlockHashes(hashes), peerId) =>
      val maybePeer = peersToDownloadFrom.find(peer => peer._1.id == peerId)
      //we allow asking for new hashes when we are not syncing and we can download from specified peer ,we are
      //top of the chain and not resolving branches currently
      if (notDownloading() && topOfTheChain && maybePeer.isDefined) {
        val (peer, _) = maybePeer.get
        val hashesToCheck = hashes.take(syncConfig.maxNewHashes)

        if (!containsAncientBlockHash(hashesToCheck)) {
          val filteredHashes = getValidHashes(hashesToCheck)

          if (filteredHashes.nonEmpty) {
            val request = GetSignedBlockHeaders(Right(filteredHashes.head.hash), filteredHashes.length, BigInt(0), reverse = false)
            requestBlockHeaders(peer, request)
            cancelScheduledResume()
          } else {
            log.debug("All received hashes all already in Chain, or Queue ")
          }
        } else {
          blacklist(peerId, blacklistDuration, "received ancient blockHash")
        }
      }
  }

  private def getValidHashes(unfilteredHashes: Seq[BlockHash]): Seq[BlockHash] = unfilteredHashes.foldLeft(Seq.empty[BlockHash])((hashesList, blockHash) =>
    ledger.checkBlockStatus(blockHash.hash) match {
      case InChain =>
        log.debug(s"BlockHash with Number: ${blockHash.number} and Hash: ${Hex.toHexString(blockHash.hash.toArray)} already in chain")
        hashesList
      case Queued =>
        log.debug(s"BlockHash with Number: ${blockHash.number} and Hash: ${Hex.toHexString(blockHash.hash.toArray)} already in queue")
        hashesList
      case UnknownBlock =>
        log.debug(s"Preparing to download unknown block with number ${blockHash.number} and hash ${Hex.toHexString(blockHash.hash.toArray)}")
        hashesList :+ blockHash
    }
  )

  private def containsAncientBlockHash(hashes: Seq[BlockHash]): Boolean = {
    val currentBestBlock = appStateStorage.getBestBlockNumber()
    hashes.exists(bh => ancientBlockHash(bh, currentBestBlock))
  }

  private def ancientBlockHash(blockHash: BlockHash, currentBestBlockNumber: BigInt) =
    currentBestBlockNumber > blockHash.number && currentBestBlockNumber - blockHash.number > syncConfig.maxNewBlockHashAge

  def handleResponseToRequest: Receive = {
    case ResponseReceived(peer: Peer, SignedBlockHeaders(signedHeaders), timeTaken) =>
      log.info("Received {} block headers in {} ms", signedHeaders.size, timeTaken)
      waitingForActor = None
      if (resolvingBranches) handleBlockBranchResolution(peer, signedHeaders.reverse)
      else handleDownload(peer, signedHeaders)

    case ResponseReceived(peer, BlockBodies(blockBodies), timeTaken) =>
      log.info("Received {} block bodies in {} ms", blockBodies.size, timeTaken)
      waitingForActor = None
      handleBlockBodies(peer, blockBodies)

    case PeerRequestHandler.RequestFailed(peer, reason) if waitingForActor.contains(sender()) =>
      waitingForActor = None
      if (handshakedPeers.contains(peer)) {
        blacklist(peer.id, blacklistDuration, reason)
      }
      scheduleResume()
  }

  def handleMinedBlock: Receive = {

    //todo improve mined block handling - add info that block was not included because of syncing [EC-250]
    //we allow inclusion of mined block only if we are not syncing / reorganising chain
    case MinedBlock(block) =>
      if (notDownloading()) {
        val importResult = ledger.importBlock(block)

        importResult match {
          case BlockImportedToTop(blocks, totalDifficulties) =>
            log.debug(s"Added new mined block ${block.signedHeader.header.number} to top of the chain")
            broadcastBlocks(blocks, totalDifficulties)
            updateTxAndOmmerPools(blocks, Nil)

          case ChainReorganised(oldBranch, newBranch, totalDifficulties) =>
            log.debug(s"Added new mined block ${block.signedHeader.header.number} resulting in chain reorganization")
            broadcastBlocks(newBranch, totalDifficulties)
            updateTxAndOmmerPools(newBranch, oldBranch)

          case DuplicateBlock =>
            log.warning(s"Mined block is a duplicate, this should never happen")

          case BlockEnqueued =>
            log.debug(s"Mined block ${block.signedHeader.header.number} was added to the queue")
            ommersPool ! AddOmmers(block.signedHeader)

          case UnknownParent =>
            log.warning(s"Mined block has no parent on the main chain")

          case BlockImportFailed(err) =>
            log.warning(s"Failed to execute mined block because of $err")
        }

      } else {
        ommersPool ! AddOmmers(block.signedHeader)
      }
  }

  private def askForHeaders() = {
    bestPeer match {
      case Some(peer) =>
        val blockNumber = appStateStorage.getBestBlockNumber()
        requestBlockHeaders(peer, GetSignedBlockHeaders(Left(blockNumber + 1), blockHeadersPerRequest, skip = 0, reverse = false))
        resolvingBranches = false

      case None =>
        log.debug("No peers to download from")
        scheduleResume()
    }
  }

  private def handleBlockBranchResolution(peer: Peer, message: Seq[SignedBlockHeader]) = {
    if (message.nonEmpty && message.last.hash == headersQueue.head.header.parentHash) {
      headersQueue = message ++ headersQueue
      processBlockHeaders(peer, headersQueue)
    } else {
      //we did not get previous blocks, there is no way to resolve, blacklist peer and continue download
      resumeWithDifferentPeer(peer)
    }
  }

  private def handleDownload(peer: Peer, message: Seq[SignedBlockHeader]) = if (message.nonEmpty) {
    headersQueue = message
    processBlockHeaders(peer, message)
  } else {
    //no new headers to process, schedule to ask again in future, we are at the top of chain
    topOfTheChain = true
    scheduleResume()
  }

  private def processBlockHeaders(peer: Peer, signedHeaders: Seq[SignedBlockHeader]) = ledger.resolveBranch(signedHeaders) match {
    case NewBetterBranch(oldBranch) =>
      val transactionsToAdd = oldBranch.flatMap(_.body.transactionList)
      pendingTransactionsManager ! PendingTransactionsManager.AddTransactions(transactionsToAdd.toList)
      val hashes = signedHeaders.take(blockBodiesPerRequest).map(_.hash)
      requestBlockBodies(peer, GetBlockBodies(hashes))

      //add first block from branch as ommer
      oldBranch.headOption.foreach { sbh => ommersPool ! AddOmmers(sbh.signedHeader) }

    case NoChainSwitch =>
      //add first block from branch as ommer
      headersQueue.headOption.foreach { sbh => ommersPool ! AddOmmers(sbh) }
      scheduleResume()

    case UnknownBranch =>
      if ((headersQueue.length - 1) / branchResolutionBatchSize >= branchResolutionMaxRequests) {
        log.debug("fail to resolve branch, branch too long, it may indicate malicious peer")
        resumeWithDifferentPeer(peer)
      } else {
        val request = GetSignedBlockHeaders(Right(headersQueue.head.header.parentHash), branchResolutionBatchSize, skip = 0, reverse = true)
        requestBlockHeaders(peer, request)
        resolvingBranches = true
      }

    case InvalidBranch =>
      log.debug("Got block header that does not have parent")
      resumeWithDifferentPeer(peer)
  }

  private def requestBlockHeaders(peer: Peer, msg: GetSignedBlockHeaders): Unit = {
    waitingForActor = Some(context.actorOf(
      PeerRequestHandler.props[GetSignedBlockHeaders, SignedBlockHeaders](
        peer, peerResponseTimeout, etcPeerManager, peerEventBus,
        requestMsg = msg,
        responseMsgCode = SignedBlockHeaders.code)))
  }

  private def requestBlockBodies(peer: Peer, msg: GetBlockBodies): Unit = {
    waitingForActor = Some(context.actorOf(
      PeerRequestHandler.props[GetBlockBodies, BlockBodies](
        peer, peerResponseTimeout, etcPeerManager, peerEventBus,
        requestMsg = msg,
        responseMsgCode = BlockBodies.code)))
  }

  private def handleBlockBodies(peer: Peer, m: Seq[BlockBody]) = {
    if (m.nonEmpty && headersQueue.nonEmpty) {
      val blocks = headersQueue.zip(m).map{ case (header, body) => Block(header, body) }

      @tailrec
      def importBlocks(blocks: List[Block], importedBlocks: List[Block] = Nil): (List[Block], Option[BlockImportResult]) =
        blocks match {
          case Nil =>
            (importedBlocks, None)

          case block :: tail =>
            ledger.importBlock(block) match {
              case BlockImportedToTop(_, _) =>
                importBlocks(tail, block :: importedBlocks)

              case ChainReorganised(_, newBranch, _) =>
                importBlocks(tail, newBranch.reverse ::: importedBlocks)

              case r @ (DuplicateBlock | BlockEnqueued) =>
                importBlocks(tail, importedBlocks)

              case err @ (UnknownParent | BlockImportFailed(_)) =>
                (importedBlocks, Some(err))
            }
        }

      val (importedBlocks, errorOpt) = importBlocks(blocks.toList)

      if (importedBlocks.nonEmpty) {
        log.debug(s"got new blocks up till block: ${importedBlocks.last.signedHeader.header.number} " +
          s"with hash ${Hex.toHexString(importedBlocks.last.signedHeader.hash.toArray[Byte])}")
      }
      errorOpt match {
        case Some(error) =>
          resumeWithDifferentPeer(peer, reason = s"a block execution error: ${error.toString}")

        case None =>
          headersQueue = headersQueue.drop(blocks.length)
          if (headersQueue.nonEmpty) {
            val hashes = headersQueue.take(blockBodiesPerRequest).map(_.hash)
            requestBlockBodies(peer, GetBlockBodies(hashes))
          } else {
            context.self ! ResumeRegularSync
          }
      }
    } else {
      //we got empty response for bodies from peer but we got block headers earlier
      resumeWithDifferentPeer(peer)
    }
  }

  private def scheduleResume() = {
    headersQueue = Nil
    resumeRegularSyncTimeout = Some(scheduler.scheduleOnce(checkForNewBlockInterval, self, ResumeRegularSync))
  }

  private def resumeWithDifferentPeer(currentPeer: Peer, reason: String = "error in response") = {
    blacklist(currentPeer.id, blacklistDuration, reason)
    headersQueue = Nil
    context.self ! ResumeRegularSync
  }

  private def bestPeer: Option[Peer] = {
    val peersToUse = peersToDownloadFrom
      .collect {
        case (ref, PeerInfo(_, totalDifficulty, true, _)) => (ref, totalDifficulty)
      }

    if (peersToUse.nonEmpty) Some(peersToUse.maxBy { case (_, td) => td }._1)
    else None
  }

  private def updateTxAndOmmerPools(blocksAdded: Seq[Block], blocksRemoved: Seq[Block]): Unit = {
    blocksRemoved.headOption.foreach(block => ommersPool ! AddOmmers(block.signedHeader))
    blocksRemoved.foreach(block => pendingTransactionsManager ! AddTransactions(block.body.transactionList.toList))

    blocksAdded.foreach { block =>
      ommersPool ! RemoveOmmers(block.signedHeader :: block.body.uncleNodesList.toList)
      pendingTransactionsManager ! RemoveTransactions(block.body.transactionList)
    }
  }

  private def broadcastBlocks(blocks: Seq[Block], totalDifficulties: Seq[BigInt]): Unit = {
    blocks.zip(totalDifficulties).foreach { case (block, td) =>
      broadcaster.broadcastBlock(NewBlock(block, td), handshakedPeers)
    }
  }

  private def notDownloading(): Boolean =
    headersQueue.isEmpty && waitingForActor.isEmpty && !resolvingBranches

}

object RegularSync {
  // scalastyle:off parameter.number
  def props(appStateStorage: AppStateStorage, etcPeerManager: ActorRef, peerEventBus: ActorRef, ommersPool: ActorRef,
      pendingTransactionsManager: ActorRef, broadcaster: BlockBroadcast, ledger: Ledger,
      syncConfig: SyncConfig, slotTimeConverter: SlotTimeConverter, scheduler: Scheduler): Props =
    Props(new RegularSync(appStateStorage, etcPeerManager, peerEventBus, ommersPool, pendingTransactionsManager,
      broadcaster, ledger, syncConfig, slotTimeConverter, scheduler))

  private[sync] case object ResumeRegularSync
  private case class ResolveBranch(peer: ActorRef)
  private case object PrintStatus

  case object Start

  /**
    * This start the actor without asking for headers, currently only used in tests
    */
  case object StartIdle

  case class MinedBlock(block: Block)
}
