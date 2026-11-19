package com.odasi.fieldwright.feature.vault

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a [PacketExport] under the app's cache and opens the platform share sheet.
 *
 * The cache is intentional: an export is a convenience copy for the requester or the
 * operator's own records, never a new seal. The app's [FileProvider] (`${applicationId}.fileprovider`,
 * cache path `packet-exports/`) grants read access to the receiving app only, for this share.
 *
 * Returns false when the share sheet could not be opened, so the screen can say so instead of
 * claiming the packet was delivered.
 */
internal fun sharePacketExport(context: Context, share: PacketShare): Boolean = runCatching {
    val directory = File(context.cacheDir, "packet-exports").apply { mkdirs() }
    val jsonFile = File(directory, share.fileName)
    jsonFile.writeText(share.canonicalJson)

    val baseName = share.fileName.substringBeforeLast('.', share.fileName)
    val custodyFile = File(directory, "$baseName-custody.txt")
    custodyFile.writeText(
        buildString {
            if (share.demo) appendLine("SAMPLE — demonstration data, not a live record.")
            appendLine("Vanguard Field custody record")
            appendLine("Packet: ${share.packetId}")
            appendLine("Records: ${share.recordCount}")
            appendLine("Chain head: ${share.chainHead}")
            if (share.demo) appendLine("Demo packets are excluded from the public log; this export is not evidence.")
            appendLine()
            append(share.custodyText)
        },
    )

    val authority = context.packageName + ".fileprovider"
    val uris = arrayListOf(
        FileProvider.getUriForFile(context, authority, jsonFile),
        FileProvider.getUriForFile(context, authority, custodyFile),
    )
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_SUBJECT, "Vanguard Field packet ${share.packetId}")
        putExtra(
            Intent.EXTRA_TEXT,
            if (share.demo) {
                "Vanguard Field SAMPLE packet ${share.packetId} — demonstration data, excluded from the log."
            } else {
                "Vanguard Field packet ${share.packetId} · ${share.recordCount} records · chain head ${share.chainHead.take(8)}."
            },
        )
    }
    context.startActivity(
        Intent.createChooser(intent, "Share packet").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    true
}.getOrElse { false }
