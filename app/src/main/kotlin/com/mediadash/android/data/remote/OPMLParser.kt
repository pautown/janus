package com.mediadash.android.data.remote

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parser for OPML (Outline Processor Markup Language) files.
 * Extracts podcast feed URLs from OPML subscription exports.
 */
class OPMLParser {

    data class OPMLFeed(
        val title: String,
        val xmlUrl: String
    )

    /**
     * Parse OPML content from a string and extract podcast feed URLs.
     */
    fun parseOPML(xmlContent: String): List<OPMLFeed> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(xmlContent.byteInputStream())
            document.documentElement.normalize()

            extractFeeds(document.documentElement)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Parse OPML content from an InputStream.
     */
    fun parseOPML(inputStream: InputStream): List<OPMLFeed> {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(inputStream)
            document.documentElement.normalize()

            extractFeeds(document.documentElement)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractFeeds(element: Element): List<OPMLFeed> {
        val feeds = mutableListOf<OPMLFeed>()

        // Find all outline elements
        val outlines = element.getElementsByTagName("outline")

        for (i in 0 until outlines.length) {
            val node = outlines.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val outline = node as Element

                // Check if this outline has an xmlUrl (podcast feed)
                val xmlUrl = outline.getAttribute("xmlUrl")
                if (xmlUrl.isNotBlank()) {
                    val title = outline.getAttribute("text")
                        .ifBlank { outline.getAttribute("title") }
                        .ifBlank { "Unknown Podcast" }

                    feeds.add(OPMLFeed(title = title, xmlUrl = xmlUrl))
                }
            }
        }

        return feeds
    }
}
