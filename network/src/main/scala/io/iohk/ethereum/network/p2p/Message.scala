package io.iohk.ethereum.network.p2p

import akka.util.ByteString

object Message {
  type Version = Int
}

trait Message {
  def code: Int
}

trait MessageSerializable extends Message {
  def toBytes(implicit di: DummyImplicit): ByteString
  def toBytes: Array[Byte]
}

trait MessageDecoder {
  def fromBytes(`type`: Int, payload: Array[Byte], protocolVersion: Message.Version): Message
}