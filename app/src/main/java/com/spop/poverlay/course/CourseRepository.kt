package com.spop.poverlay.course

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * Courses are plain-text files in the app's external files folder:
 *   /sdcard/Android/data/<package>/files/courses/<id>.txt
 * That path needs no storage permission and can be written from a computer with
 *   adb push my-course.txt /sdcard/Android/data/<package>/files/courses/
 * Bundled sample courses (assets/courses) are copied in the first time the folder is empty.
 */
class CourseRepository(private val context: Context) {
    companion object {
        const val FOLDER = "courses"
        private const val ASSET_FOLDER = "courses"
    }

    val folder: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER).apply { mkdirs() }

    fun ensureSamples() {
        val dir = folder
        if (dir.listFiles { f -> f.extension == "txt" }?.isNotEmpty() == true) return
        val assets = context.assets
        val names = try { assets.list(ASSET_FOLDER) ?: emptyArray() } catch (e: Exception) { emptyArray() }
        for (name in names) {
            try {
                assets.open("$ASSET_FOLDER/$name").use { input ->
                    File(dir, name).outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to copy sample course $name")
            }
        }
    }

    /** Returns parsed courses plus any files that failed to parse, with the reason. */
    fun load(): Pair<List<Course>, List<Pair<String, String>>> {
        ensureSamples()
        val good = ArrayList<Course>()
        val bad = ArrayList<Pair<String, String>>()
        folder.listFiles { f -> f.extension == "txt" }?.sortedBy { it.name }?.forEach { file ->
            val id = file.nameWithoutExtension
            try {
                good += CourseParser.parse(id, file.readText(), defaultName = id.replace('_', ' ').replace('-', ' '))
            } catch (e: CourseParseException) {
                bad += file.name to e.message.orEmpty()
            } catch (e: Exception) {
                bad += file.name to (e.message ?: e.toString())
            }
        }
        return good to bad
    }

    fun find(id: String): Course? = load().first.firstOrNull { it.id == id }
}
