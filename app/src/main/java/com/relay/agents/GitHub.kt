package com.relay.agents

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

data class GitDiff(val added: List<String>, val modified: List<String>, val same: List<String>, val remoteOnly: Int) {
    val changed: List<String> get() = added + modified
}

class Remote(val commit: String?, val tree: String?, val files: Map<String, String>)

class PushResult(val commitUrl: String, val count: Int, val diff: GitDiff)

data class RunInfo(val status: String, val conclusion: String, val url: String, val title: String) {
    val done: Boolean get() = status == "completed"
}

/**
 * عميل GitHub REST: مقارنة (سحب شجرة المستودع) ثم رفع التغييرات فقط في commit واحد ذري.
 * لا force push إطلاقاً، ولا حذف لملفات موجودة فقط في المستودع.
 */
object GitHubClient {
    private const val API = "https://api.github.com"

    class Resp(val code: Int, val body: String)

    /** نفس حساب git لقيمة blob: sha1("blob <size>\u0000" + bytes). */
    fun blobSha(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun diff(local: Map<String, ByteArray>, remote: Map<String, String>): GitDiff {
        val added = ArrayList<String>()
        val modified = ArrayList<String>()
        val same = ArrayList<String>()
        for ((path, content) in local.toSortedMap()) {
            val r = remote[path]
            when {
                r == null -> added.add(path)
                r == blobSha(content) -> same.add(path)
                else -> modified.add(path)
            }
        }
        return GitDiff(added, modified, same, remote.keys.count { it !in local })
    }

    private fun call(method: String, path: String, token: String, body: JSONObject? = null): Resp {
        val c = URL(API + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            c.setRequestProperty("User-Agent", "ClaudeRelay")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Resp(code, text)
        } finally {
            c.disconnect()
        }
    }

    private fun fail(r: Resp): String {
        val msg = runCatching { JSONObject(r.body).optString("message") }.getOrDefault("")
        val hint = when (r.code) {
            401 -> " (token invalid or expired)"
            403 -> " (token lacks permission: needs repo scope, plus workflow for Actions)"
            404 -> " (repository/branch not found, or token cannot see it)"
            else -> ""
        }
        val wf = if (msg.contains("workflow", true)) " (pushing .github/workflows needs the workflow scope)" else ""
        return "GitHub ${r.code}: ${msg.ifEmpty { "error" }}$hint$wf"
    }

    private fun ok(r: Resp): Resp = if (r.code in 200..299) r else throw IOException(fail(r))

    fun remote(token: String, owner: String, repo: String, branch: String): Result<Remote> = runCatching {
        val ref = call("GET", "/repos/$owner/$repo/git/ref/heads/$branch", token)
        if (ref.code == 409) return@runCatching Remote(null, null, emptyMap()) // مستودع فارغ
        ok(ref)
        val commit = JSONObject(ref.body).getJSONObject("object").getString("sha")
        val cm = ok(call("GET", "/repos/$owner/$repo/git/commits/$commit", token))
        val tree = JSONObject(cm.body).getJSONObject("tree").getString("sha")
        val tr = ok(call("GET", "/repos/$owner/$repo/git/trees/$tree?recursive=1", token))
        val arr = JSONObject(tr.body).getJSONArray("tree")
        val files = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            if (e.optString("type") == "blob") files[e.getString("path")] = e.getString("sha")
        }
        Remote(commit, tree, files)
    }

    /** يرفع الملفات المتغيّرة فقط في commit واحد؛ يحدّث الفرع دون force. */
    fun push(
        token: String, owner: String, repo: String, branch: String,
        files: Map<String, ByteArray>, message: String
    ): Result<PushResult> = runCatching {
        var rem = remote(token, owner, repo, branch).getOrThrow()
        if (rem.commit == null) {
            // مستودع فارغ: ملف README أولي ينشئ الفرع الافتراضي.
            val init = JSONObject()
                .put("message", "Initialize repository")
                .put("content", Base64.getEncoder().encodeToString("# $repo\n".toByteArray()))
                .put("branch", branch)
            ok(call("PUT", "/repos/$owner/$repo/contents/README.md", token, init))
            rem = remote(token, owner, repo, branch).getOrThrow()
        }
        val d = diff(files, rem.files)
        if (d.changed.isEmpty()) return@runCatching PushResult("", 0, d)

        val entries = org.json.JSONArray()
        for (path in d.changed) {
            val blob = JSONObject()
                .put("content", Base64.getEncoder().encodeToString(files.getValue(path)))
                .put("encoding", "base64")
            val sha = JSONObject(ok(call("POST", "/repos/$owner/$repo/git/blobs", token, blob)).body).getString("sha")
            entries.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", sha))
        }
        val treeBody = JSONObject().put("base_tree", rem.tree).put("tree", entries)
        val newTree = JSONObject(ok(call("POST", "/repos/$owner/$repo/git/trees", token, treeBody)).body).getString("sha")
        val commitBody = JSONObject().put("message", message)
            .put("tree", newTree).put("parents", org.json.JSONArray().put(rem.commit))
        val newCommit = JSONObject(ok(call("POST", "/repos/$owner/$repo/git/commits", token, commitBody)).body).getString("sha")
        val refBody = JSONObject().put("sha", newCommit).put("force", false)
        ok(call("PATCH", "/repos/$owner/$repo/git/refs/heads/$branch", token, refBody))
        PushResult("https://github.com/$owner/$repo/commit/$newCommit", d.changed.size, d)
    }

    /** يشغّل سير عمل GitHub Actions (يحتاج workflow_dispatch في الملف وصلاحية workflow للرمز). */
    fun dispatch(token: String, owner: String, repo: String, workflow: String, branch: String): Result<Unit> = runCatching {
        ok(call("POST", "/repos/$owner/$repo/actions/workflows/$workflow/dispatches", token, JSONObject().put("ref", branch)))
        Unit
    }

    fun latestRun(token: String, owner: String, repo: String, branch: String): Result<RunInfo?> = runCatching {
        val r = ok(call("GET", "/repos/$owner/$repo/actions/runs?per_page=1&branch=$branch", token))
        val arr = JSONObject(r.body).getJSONArray("workflow_runs")
        if (arr.length() == 0) return@runCatching null
        val o = arr.getJSONObject(0)
        fun s(k: String) = if (o.isNull(k)) "" else o.getString(k)
        RunInfo(s("status"), s("conclusion"), s("html_url"), s("display_title"))
    }
}
