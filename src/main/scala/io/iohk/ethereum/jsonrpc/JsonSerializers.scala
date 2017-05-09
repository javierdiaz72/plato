package io.iohk.ethereum.jsonrpc

import akka.util.ByteString
import org.json4s.JsonAST.{JNull, JString}
import org.json4s.{CustomSerializer, DefaultFormats, Extraction, JValue}
import org.spongycastle.util.encoders.Hex

object JsonSerializers {

  object UnformattedDataJsonSerializer extends CustomSerializer[ByteString](_ =>
    (
      { PartialFunction.empty },
      { case bs: ByteString => JString(s"0x${Hex.toHexString(bs.toArray)}") }
    )
  )

  object QuantitiesSerializer extends CustomSerializer[BigInt](_ =>
    (
      {PartialFunction.empty},
      {
        case n: BigInt =>
          if(n == 0)
            JString("0x0")
          else
            JString(s"0x${Hex.toHexString(n.toByteArray).dropWhile(_ == '0')}")
      }
    )
  )

  object OptionNoneToJNullSerializer extends CustomSerializer[Option[_]](formats =>
    (
      {PartialFunction.empty},
      { case None => JNull }
    )
  )

  object SignedTransactionResponseSerializer extends CustomSerializer[TransactionResponse](_ =>
    (
      {PartialFunction.empty},
      { case stxResponse: TransactionResponse =>
          implicit val formats = DefaultFormats.preservingEmptyValues +
            UnformattedDataJsonSerializer +
            QuantitiesSerializer +
            OptionNoneToJNullSerializer

          Extraction.decompose(stxResponse)
      }
    )
  )

  object BlockResponseSerializer extends CustomSerializer[BlockResponse](_ =>
    (
      {PartialFunction.empty},
      { case blockResponse: BlockResponse =>
          implicit val formats = DefaultFormats.preservingEmptyValues +
            UnformattedDataJsonSerializer +
            QuantitiesSerializer +
            OptionNoneToJNullSerializer +
            SignedTransactionResponseSerializer

          Extraction.decompose(blockResponse)
      }
    )
  )

}
