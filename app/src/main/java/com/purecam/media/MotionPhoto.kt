package com.purecam.media

import java.io.ByteArrayOutputStream

/**
 * Builds a "Motion Photo" (Google Camera format v1, plus the older MicroVideo tags): a normal JPEG
 * with an XMP block describing an MP4 appended after the image data. Google Photos, Xiaomi Gallery
 * and most modern galleries play it as a live photo; every other app just sees a JPEG.
 */
object MotionPhoto {
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    fun build(jpeg: ByteArray, mp4: ByteArray, presentationUs: Long): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Not a JPEG" }
        val xmp = xmp(mp4.size, presentationUs)
        val payload = XMP_HEADER.toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        require(payload.size + 2 <= 0xFFFF) { "XMP too large" }

        val out = ByteArrayOutputStream(jpeg.size + mp4.size + payload.size + 16)
        out.write(jpeg, 0, 2) // SOI
        var pos = 2
        // Walk the leading APPn segments: keep EXIF/JFIF, drop any existing XMP, insert ours after them.
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker !in 0xE0..0xEF) break
            val len = ((jpeg[pos + 2].toInt() and 0xFF) shl 8) or (jpeg[pos + 3].toInt() and 0xFF)
            val end = pos + 2 + len
            if (end > jpeg.size) break
            val isXmp = marker == 0xE1 && startsWith(jpeg, pos + 4, XMP_HEADER)
            if (!isXmp) out.write(jpeg, pos, end - pos)
            pos = end
        }
        val segLen = payload.size + 2
        out.write(0xFF); out.write(0xE1)
        out.write((segLen shr 8) and 0xFF); out.write(segLen and 0xFF)
        out.write(payload)
        out.write(jpeg, pos, jpeg.size - pos)
        out.write(mp4)
        return out.toByteArray()
    }

    private fun startsWith(data: ByteArray, at: Int, s: String): Boolean {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        if (at + b.size > data.size) return false
        for (i in b.indices) if (data[at + i] != b[i]) return false
        return true
    }

    private fun xmp(videoLength: Int, presentationUs: Long) = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="PureCam">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
      GCamera:MotionPhoto="1"
      GCamera:MotionPhotoVersion="1"
      GCamera:MotionPhotoPresentationTimestampUs="$presentationUs"
      GCamera:MicroVideo="1"
      GCamera:MicroVideoVersion="1"
      GCamera:MicroVideoOffset="$videoLength"
      GCamera:MicroVideoPresentationTimestampUs="$presentationUs">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>"""
}
