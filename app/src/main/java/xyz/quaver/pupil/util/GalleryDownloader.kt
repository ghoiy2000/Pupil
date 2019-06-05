package xyz.quaver.pupil.util

import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Environment
import android.util.SparseArray
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.list
import xyz.quaver.hitomi.*
import xyz.quaver.pupil.Pupil
import xyz.quaver.pupil.R
import xyz.quaver.pupil.ReaderActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import kotlin.collections.ArrayList
import kotlin.concurrent.schedule

class GalleryDownloader(
    base: Context,
    private val galleryBlock: GalleryBlock,
    _notify: Boolean = false
) : ContextWrapper(base) {

    val downloads = (applicationContext as Pupil).downloads

    var download: Boolean = false
        set(value) {
            if (value) {
                field = true
                notificationManager.notify(galleryBlock.id, notificationBuilder.build())

                val data = File(ContextCompat.getDataDir(this), "images/${galleryBlock.id}")
                val cache = File(cacheDir, "imageCache/${galleryBlock.id}")

                if (cache.exists() && !data.exists()) {
                    cache.copyRecursively(data, true)
                    cache.deleteRecursively()
                }

                if (!reader.isActive && downloadJob?.isActive != true)
                    field = false

                downloads.add(galleryBlock.id)
            } else {
                field = false

                downloads.remove(galleryBlock.id)
            }

            onNotifyChangedHandler?.invoke(value)
        }

    private val reader: Deferred<Reader>
    private var downloadJob: Job? = null

    private lateinit var notificationBuilder: NotificationCompat.Builder
    private lateinit var notificationManager: NotificationManagerCompat

    var onReaderLoadedHandler: ((Reader) -> Unit)? = null
    var onProgressHandler: ((Int) -> Unit)? = null
    var onDownloadedHandler: ((List<String>) -> Unit)? = null
    var onErrorHandler: ((Exception) -> Unit)? = null
    var onCompleteHandler: (() -> Unit)? = null
    var onNotifyChangedHandler: ((Boolean) -> Unit)? = null

    companion object : SparseArray<GalleryDownloader>()

    init {
        put(galleryBlock.id, this)

        initNotification()

        reader = CoroutineScope(Dispatchers.IO).async {
            download = _notify
            val json = Json(JsonConfiguration.Stable)
            val serializer = ReaderItem.serializer().list
            val preference = PreferenceManager.getDefaultSharedPreferences(this@GalleryDownloader)
            val useHiyobi = preference.getBoolean("use_hiyobi", false)

            //Check cache
            val cache = File(ContextCompat.getDataDir(this@GalleryDownloader), "images/${galleryBlock.id}/reader.json").let {
                when {
                    it.exists() -> it
                    else -> File(cacheDir, "imageCache/${galleryBlock.id}/reader.json")
                }
            }

            if (cache.exists()) {
                val cached = json.parse(serializer, cache.readText())

                if (cached.isNotEmpty()) {
                    onReaderLoadedHandler?.invoke(cached)
                    return@async cached
                }
            }

            //Cache doesn't exist. Load from internet
            val reader = when {
                useHiyobi -> {
                    xyz.quaver.hiyobi.getReader(galleryBlock.id).let {
                        when {
                            it.isEmpty() -> getReader(galleryBlock.id)
                            else -> it
                        }
                    }
                }
                else -> {
                    getReader(galleryBlock.id)
                }
            }

            if (reader.isNotEmpty()) {
                //Save cache
                if (!cache.parentFile.exists())
                    cache.parentFile.mkdirs()

                cache.writeText(json.stringify(serializer, reader))
            }

            reader
        }
    }

    private fun webpUrlFromUrl(url: String) = url.replace("/galleries/", "/webp/") + ".webp"

    fun start() {
        downloadJob = CoroutineScope(Dispatchers.Default).launch {
            val reader = reader.await()

            if (reader.isEmpty())
                onErrorHandler?.invoke(IOException("Couldn't retrieve Reader"))

            val list = ArrayList<String>()

            onReaderLoadedHandler?.invoke(reader)

            notificationBuilder
                .setProgress(reader.size, 0, false)
                .setContentText("0/${reader.size}")

            reader.chunked(4).forEachIndexed { chunkIndex, chunked ->
                chunked.mapIndexed { i, it ->
                    val index = chunkIndex*4+i

                    onProgressHandler?.invoke(index)

                    notificationBuilder
                        .setProgress(reader.size, index, false)
                        .setContentText("$index/${reader.size}")

                    if (download)
                        notificationManager.notify(galleryBlock.id, notificationBuilder.build())

                    async(Dispatchers.IO) {
                        val url = if (it.galleryInfo?.haswebp == 1) webpUrlFromUrl(it.url) else it.url

                        val name = "$index".padStart(4, '0')
                        val ext = url.split('.').last()

                        val cache = File(ContextCompat.getDataDir(this@GalleryDownloader), "images/${galleryBlock.id}/images/$name.$ext").let {
                            when {
                                it.exists() -> it
                                else -> File(cacheDir, "/imageCache/${galleryBlock.id}/images/$name.$ext")
                            }
                        }

                        if (!cache.exists())
                            try {
                                with(URL(url).openConnection() as HttpsURLConnection) {
                                    setRequestProperty("Referer", getReferer(galleryBlock.id))

                                    if (!cache.parentFile.exists())
                                        cache.parentFile.mkdirs()

                                    inputStream.copyTo(FileOutputStream(cache))
                                }
                            } catch (e: Exception) {
                                cache.delete()

                                downloads.remove(galleryBlock.id)

                                onErrorHandler?.invoke(e)

                                notificationBuilder
                                    .setContentTitle(galleryBlock.title)
                                    .setContentText(getString(R.string.reader_notification_error))
                                    .setProgress(0, 0, false)

                                notificationManager.notify(galleryBlock.id, notificationBuilder.build())
                            }

                        cache.absolutePath
                    }
                }.forEach {
                    list.add(it.await())
                    onDownloadedHandler?.invoke(list)
                }
            }

            onCompleteHandler?.invoke()

            Timer(false).schedule(1000) {
                notificationBuilder
                    .setContentTitle(galleryBlock.title)
                    .setContentText(getString(R.string.reader_notification_complete))
                    .setProgress(0, 0, false)

                if (download) {
                    File(cacheDir, "imageCache/${galleryBlock.id}").let {
                        if (it.exists()) {
                            it.copyRecursively(
                                File(ContextCompat.getDataDir(this@GalleryDownloader), "images/${galleryBlock.id}"),
                                true
                            )
                            it.deleteRecursively()
                        }
                    }

                    notificationManager.notify(galleryBlock.id, notificationBuilder.build())
                }

                download = false
            }

            remove(galleryBlock.id)
        }
    }

    fun cancel() {
        downloadJob?.cancel()

        remove(galleryBlock.id)
    }

    suspend fun cancelAndJoin() {
        downloadJob?.cancelAndJoin()

        remove(galleryBlock.id)
    }

    fun invokeOnReaderLoaded() {
        CoroutineScope(Dispatchers.Default).launch {
            onReaderLoadedHandler?.invoke(reader.await())
        }
    }

    fun clearNotification() {
        notificationManager.cancel(galleryBlock.id)
    }

    fun invokeOnNotifyChanged() {
        onNotifyChangedHandler?.invoke(download)
    }

    private fun initNotification() {
        val intent = Intent(this, ReaderActivity::class.java).apply {
            putExtra("galleryblock", Json(JsonConfiguration.Stable).stringify(GalleryBlock.serializer(), galleryBlock))
        }
        val pendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(intent)
            getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
        }

        notificationBuilder = NotificationCompat.Builder(this, "download").apply {
            setContentTitle(galleryBlock.title)
            setContentText(getString(R.string.reader_notification_text))
            setSmallIcon(R.drawable.ic_download)
            setContentIntent(pendingIntent)
            setProgress(0, 0, true)
            priority = NotificationCompat.PRIORITY_LOW
        }
        notificationManager = NotificationManagerCompat.from(this)
    }

    fun export(onSuccess: (() -> Unit)? = null, onError: (() -> Unit)? = null) {
        val images = File(ContextCompat.getDataDir(this), "images/${galleryBlock.id}/images").let {
            when {
                it.exists() -> it
                else -> File(cacheDir, "imageCache/${galleryBlock.id}/images")
            }
        }

        if (!images.exists())
            return

        CoroutineScope(Dispatchers.Default).launch {
            val preference = PreferenceManager.getDefaultSharedPreferences(this@GalleryDownloader)
            val zip = preference.getBoolean("export_zip", false)

            if (zip) {
                var target = File(Environment.getExternalStorageDirectory(), "Pupil/${galleryBlock.id} ${galleryBlock.title}.zip")

                try {
                    target.createNewFile()
                } catch (e: IOException) {
                    target = File(Environment.getExternalStorageDirectory(), "Pupil/${galleryBlock.id}.zip")

                    try {
                        target.createNewFile()
                    } catch (e: IOException) {
                        onError?.invoke()
                        return@launch
                    }
                }

                FileOutputStream(target).use { targetStream ->
                    ZipOutputStream(targetStream).use { zipStream ->
                        images.listFiles().forEach {
                            zipStream.putNextEntry(ZipEntry(it.name))

                            FileInputStream(it).use { fileStream ->
                                fileStream.copyTo(zipStream)
                            }
                        }
                    }
                }
            } else {
                var target = File(Environment.getExternalStorageDirectory(), "Pupil/${galleryBlock.id} ${galleryBlock.title}")

                try {
                    target.canonicalPath
                } catch (e: IOException) {
                    target = File(Environment.getExternalStorageDirectory(), "Pupil/${galleryBlock.id}")

                    try {
                        target.canonicalPath
                    } catch (e: IOException) {
                        onError?.invoke()
                        return@launch
                    }
                }

                images.copyRecursively(target, true)
            }

            onSuccess?.invoke()
        }
    }

}