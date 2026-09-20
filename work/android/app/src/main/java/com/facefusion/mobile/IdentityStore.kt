package com.facefusion.mobile

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A persistent identity. Every [views] entry is an app-private copy of a source photo. */
data class IdentityProfile(
    val id: String,
    val name: String,
    val views: List<Uri>,
)

/** Small, local-only store for named identities and their source photographs. */
object IdentityStore {
    private const val PREFS = "identity_profiles"
    private const val KEY = "profiles_v1"

    fun load(context: Context): List<IdentityProfile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return runCatching {
            val a = JSONArray(raw)
            buildList {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val files = o.getJSONArray("views")
                    val views = buildList {
                        for (j in 0 until files.length()) {
                            val f = File(files.getString(j))
                            if (f.isFile) add(Uri.fromFile(f))
                        }
                    }
                    if (views.isNotEmpty()) add(IdentityProfile(
                        o.getString("id"), o.optString("name", "Identity ${i + 1}"), views))
                }
            }
        }.getOrElse { emptyList() }
    }

    fun create(context: Context, uri: Uri, defaultName: String): IdentityProfile? {
        val id = UUID.randomUUID().toString()
        val copied = copyInto(context, id, uri) ?: return null
        return IdentityProfile(id, defaultName, listOf(copied))
    }

    fun addView(context: Context, profile: IdentityProfile, uri: Uri): IdentityProfile? {
        val copied = copyInto(context, profile.id, uri) ?: return null
        return profile.copy(views = profile.views + copied)
    }

    fun removeCopiedView(uri: Uri) {
        if (uri.scheme == "file") runCatching { File(uri.path!!).delete() }
    }

    fun delete(profile: IdentityProfile) {
        profile.views.forEach(::removeCopiedView)
        profile.views.firstOrNull()?.path?.let { File(it).parentFile?.delete() }
    }

    fun save(context: Context, profiles: List<IdentityProfile>) {
        val a = JSONArray()
        profiles.forEach { p ->
            a.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("views", JSONArray().apply { p.views.forEach { put(it.path) } })
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, a.toString()).apply()
    }

    private fun copyInto(context: Context, identityId: String, uri: Uri): Uri? {
        val dir = File(context.filesDir, "identities/$identityId").apply { mkdirs() }
        val out = File(dir, "view_${System.currentTimeMillis()}_${UUID.randomUUID()}.img")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { source -> out.outputStream().use { source.copyTo(it) } }
            Uri.fromFile(out)
        } catch (_: Exception) {
            out.delete()
            null
        }
    }
}
