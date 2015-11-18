package scala.reactive
package remoting



import java.io._
import java.net._
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import scala.annotation.tailrec
import scala.collection._
import scala.reactive.core.UnrolledRing



class Remoting(val system: IsoSystem) extends Protocol.Service {
  val udpTransport = new Remoting.Transport.Udp(system)

  def resolve[@spec(Int, Long, Double) T: Arrayable]
    (channelUrl: ChannelUrl): Channel[T] = {
    channelUrl.isoUrl.systemUrl.schema match {
      case "iso.udp" => udpTransport.newChannel[T](channelUrl)
      case s => sys.error("Unknown channel schema: $s")
    }
  }

  def shutdown() {
    udpTransport.shutdown()
  }
}


object Remoting {
  trait Transport {
    def newChannel[@spec(Int, Long, Double) T: Arrayable](url: ChannelUrl): Channel[T]
    def shutdown(): Unit
  }

  object Transport {
    class Udp(val system: IsoSystem) extends Transport {
      private[remoting] val datagramChannel = {
        val url = system.bundle.urlsBySchema("iso.udp")
        val ch = DatagramChannel.open()
        ch.bind(url.inetSocketAddress)
        ch
      }

      def port: Int = datagramChannel.socket.getLocalPort

      private val refSenderInstance = {
        val t = new Udp.Sender[AnyRef](
          this,
          new UnrolledRing[ChannelUrl],
          new UnrolledRing[AnyRef],
          ByteBuffer.allocateDirect(65535))
        t.start()
        t
      }

      private implicit def refSender[T] = refSenderInstance.asInstanceOf[Udp.Sender[T]]

      private implicit val intSender = {
        val t = new Udp.Sender[Int](
          this,
          new UnrolledRing[ChannelUrl],
          new UnrolledRing[Int],
          ByteBuffer.allocateDirect(65535))
        t.start()
        t
      }

      private implicit val longSender = {
        val t = new Udp.Sender[Long](
          this,
          new UnrolledRing[ChannelUrl],
          new UnrolledRing[Long],
          ByteBuffer.allocateDirect(65535))
        t.start()
        t
      }

      private implicit val doubleSender = {
        val t = new Udp.Sender[Double](
          this,
          new UnrolledRing[ChannelUrl],
          new UnrolledRing[Double],
          ByteBuffer.allocateDirect(65535))
        t.start()
        t
      }

      private val receiver = {
        val t = new Udp.Receiver(this, ByteBuffer.allocateDirect(65535))
        t.start()
        t
      }

      def newChannel[@spec(Int, Long, Double) T: Arrayable]
        (url: ChannelUrl): Channel[T] = {
        new UdpChannel[T](implicitly[Udp.Sender[T]], url)
      }

      def shutdown() {
        datagramChannel.socket.close()
        refSender.notifyEnd()
        intSender.notifyEnd()
        longSender.notifyEnd()
        doubleSender.notifyEnd()
        receiver.notifyEnd()
      }
    }

    object Udp {
      private[remoting] class Sender[@spec(Int, Long, Double) T: Arrayable](
        val udpTransport: Udp,
        val urls: UnrolledRing[ChannelUrl],
        val events: UnrolledRing[T],
        val buffer: ByteBuffer
      ) extends Thread {
        setDaemon(true)

        private[remoting] def pickle[@spec(Int, Long, Double) T]
          (isoName: String, anchor: String, x: T) {
          val pickler = udpTransport.system.bundle.pickler
          buffer.clear()
          pickler.pickle(isoName, buffer)
          pickler.pickle(anchor, buffer)
          pickler.pickle(x, buffer)
          buffer.limit(buffer.position())
          buffer.position(0)
        }

        private[remoting] def send[@spec(Int, Long, Double) T](x: T, url: ChannelUrl) {
          pickle(url.isoUrl.name, url.anchor, x)
          val sysUrl = url.isoUrl.systemUrl
          udpTransport.datagramChannel.send(buffer, sysUrl.inetSocketAddress)
        }

        def enqueue(x: T, url: ChannelUrl) {
          this.synchronized {
            urls.enqueue(url)
            events.enqueue(x)
            this.notify()
          }
        }

        def notifyEnd() {
          this.synchronized {
            this.notify()
          }
        }

        @tailrec
        final override def run() {
          var url: ChannelUrl = null
          var x: T = null.asInstanceOf[T]
          def mustEnd = udpTransport.datagramChannel.socket.isClosed
          this.synchronized {
            while (urls.isEmpty && !mustEnd) this.wait()
            if (urls.nonEmpty) {
              url = urls.dequeue()
              x = events.dequeue()
            }
          }
          if (url != null) send(x, url)
          if (!mustEnd) run()
        }
      }

      private[remoting] class Receiver(
        val udpTransport: Udp,
        val buffer: ByteBuffer
      ) extends Thread {
        def notifyEnd() {
          // no op
        }

        def receive() {
          val socketAddress = udpTransport.datagramChannel.receive(buffer)
          buffer.flip()
          val pickler = udpTransport.system.bundle.pickler
          val isoName = pickler.depickle[String](buffer)
          val channelName = pickler.depickle[String](buffer)
          val event = pickler.depickle[AnyRef](buffer)
          udpTransport.system.channels.find[AnyRef](isoName, channelName) match {
            case Some(ch) => ch ! event
            case None => // drop event -- no such channel here
          }
        }

        @tailrec
        override final def run() {
          var success = false
          try {
            buffer.clear()
            receive()
            success = true
          } catch {
            case e: Exception => // not ok
          }
          if (success) run()
        }
      }
    }

    private class UdpChannel[@spec(Int, Long, Double) T](
      sender: Udp.Sender[T], url: ChannelUrl
    ) extends Channel[T] {
      def !(x: T): Unit = sender.enqueue(x, url)
    }
  }

  /** Pickles an object into a byte buffer, so that it can be sent over the wire.
   */
  trait Pickler {
    def pickle[@spec(Int, Long, Double) T](x: T, buffer: ByteBuffer): Unit
    def depickle[@spec(Int, Long, Double) T](buffer: ByteBuffer): T
  }

  object Pickler {
    /** Pickler implementation based on Java serialization.
     */
    class JavaSerialization extends Pickler {
      def pickle[@spec(Int, Long, Double) T](x: T, buffer: ByteBuffer) = {
        val os = new ByteBufferOutputStream(buffer)
        val oos = new ObjectOutputStream(os)
        oos.writeObject(x)
      }
      def depickle[@spec(Int, Long, Double) T](buffer: ByteBuffer): T = {
        val is = new ByteBufferInputStream(buffer)
        val ois = new ObjectInputStream(is)
        ois.readObject().asInstanceOf[T]
      }
    }
  }

  private class ByteBufferOutputStream(val buf: ByteBuffer) extends OutputStream {
    def write(b: Int): Unit = buf.put(b.toByte)
    override def write(bytes: Array[Byte], off: Int, len: Int): Unit = {
      buf.put(bytes, off, len)
    }
  }

  private class ByteBufferInputStream(val buffer: ByteBuffer) extends InputStream {
    def read() = buffer.get()
    override def read(dst: Array[Byte], offset: Int, length: Int) = {
      val count = math.min(buffer.remaining, length)
      if (count == 0) -1
      else {
        buffer.get(dst, offset, length)
        count
      }
    }
  }

}