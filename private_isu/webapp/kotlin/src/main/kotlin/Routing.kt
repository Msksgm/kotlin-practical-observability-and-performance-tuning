package io.github.msksgm

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.freemarker.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.sessions.*
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rubyeye.xmemcached.MemcachedClient
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.core.kotlin.KotlinPlugin
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.jvm.optionals.getOrElse
import kotlin.jvm.optionals.getOrNull
import kotlin.random.Random

const val postsPerPage = 20
const val UploadLimit = 10 * 1024 * 1024

data class User(
    val id: Int,
    val accountName: String,
    val passhash: String,
    val authority: Int,
    val delFlg: Int,
    val createdAt: OffsetDateTime
)

data class Post(
    val id: Int,
    val userId: Int,
    var imgData: ByteArray? = null,
    val body: String,
    val mime: String,
    val createdAt: OffsetDateTime,
    var commentCount: Int = 0,
    var comments: List<Comment> = emptyList(),
    var user: User? = null,
    var csrfToken: String = "",
)

data class Comment(
    val id: Int,
    val postId: Int,
    val userId: Int,
    val comment: String,
    val createdAt: OffsetDateTime,
    var user: User? = null,
)


class MemcachedSessionStorage(
    private val client: MemcachedClient,
    private val keyPrefix: String = "isuconp-kotlin.session:",
    private val expirationSeconds: Int = 3600,
) : SessionStorage {
    override suspend fun write(id: String, value: String) {
        withContext(Dispatchers.IO) {
            client.set("$keyPrefix$id", expirationSeconds, value)
        }
    }

    override suspend fun read(id: String): String {
        return withContext(Dispatchers.IO) {
            client.get<String>("$keyPrefix$id")
                ?: throw NoSuchElementException("Session $id not found")
        }
    }

    override suspend fun invalidate(id: String) {
        withContext(Dispatchers.IO) {
            client.delete("$keyPrefix$id")
        }
    }
}

private val dataSource: HikariDataSource by lazy {
    HikariDataSource(HikariConfig().apply {
        val host = System.getenv("ISUCONP_DB_HOST") ?: "localhost"
        val port = System.getenv("ISUCONP_DB_PORT") ?: "3306"
        val name = System.getenv("ISUCONP_DB_NAME") ?: "isuconp"
        jdbcUrl = "jdbc:mysql://$host:$port/$name?useSSL=false&characterEncoding=UTF-8&connectionCollation=utf8mb4_unicode_ci&allowPublicKeyRetrieval=true"
        username = System.getenv("ISUCONP_DB_USER") ?: "root"
        password = System.getenv("ISUCONP_DB_PASSWORD") ?: "root"
        maximumPoolSize = 10
        connectionTimeout = 5000 // デフォルト 30 s。クライアントがプールからコネクションを取得するまで 待機する最大時間（ミリ秒）。
        keepaliveTime = 60000 // デフォルト 0。コネクションの死活確認。再実行時に使い回しをしようとして warning を防ぐ
        // Go リファレンス実装の database/sql は接続を遅延確立するので、
        // HikariCP の起動時接続検証 (checkFailFast) も無効化して挙動を揃える。
        // これがないと MySQL temp server 段階で構築失敗 → by lazy 再呼びループになる。
        initializationFailTimeout = -1
    })
}

private val jdbi: Jdbi by lazy {
    Jdbi.create(dataSource).installPlugin(KotlinPlugin())
}

private fun dbInitialize() {
    val sqls = listOf(
        "DELETE FROM users WHERE id > 1000",
        "DELETE FROM posts WHERE id > 10000",
        "DELETE FROM comments WHERE id > 100000",
        "UPDATE users SET del_flg = 0",
        "UPDATE users SET del_flg = 1 WHERE id % 50 = 0",
    )
    jdbi.useHandle<Exception> { h ->
        sqls.forEach { h.execute(it) }
    }
}

private fun validateUser(accountName: String?, password: String?): Boolean {
    if (accountName == null || password == null) return false
    return Regex("^[a-zA-Z0-9_]{3,}$").matches(accountName) &&
        Regex("^[a-zA-Z0-9_]{6,}$").matches(password)
}

private fun digest(src: String): String {
    val cmd = """printf "%s" ${escapeshellarg(src)} | openssl dgst -sha512 | sed 's/^.*= //'"""
    val process = ProcessBuilder("/bin/bash", "-c", cmd)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return output.trim()
}

// goの実装を参考にしたので、自前でエスケープ関数を作成
private fun escapeshellarg(s: String): String {
    return "'" + s.replace("'", "'\\''") + "'"
}

private fun calculateSalt(accountName: String): String = digest(accountName)

private fun calculatePasshash(accountName: String, password: String): String {
    return digest("$password:${calculateSalt(accountName)}")
}

private fun ApplicationCall.getCsrfToken(): String {
    return sessions.get<UserSession>()?.csrfToken ?: ""
}

private fun secureRandomStr(length: Int): String {
    return Random.nextBytes(length).joinToString("") { "%02x".format(it) }
}

private fun tryLogin(accountName: String?, password: String?): User? {
    if (accountName == null || password == null) {
        return null
    }
    val user = jdbi.withHandle<User?, Exception> { h ->
        h.createQuery("SELECT * FROM users WHERE account_name = :name AND del_flg = 0")
            .bind("name", accountName)
            .mapTo<User>()
            .findOne()
            .orElse(null)
    }

    return if (calculatePasshash(accountName, password) == user?.passhash) {
        user
    } else {
        null
    }
}

private fun makePostsNew(results: List<Post>, csrfToken: String, allComments: Boolean): List<Post> {
    if (results.isEmpty()) {
        return emptyList()
    }
    val posts = mutableListOf<Post>()

    // コメントをまとめて取得
    val postIds = results.map { it.id }
    val commentsMap = jdbi.withHandle<List<Comment>, Exception> { h ->
        h.createQuery("""
            SELECT
                comments.id as comment_id
                , comments.post_id as comment_post_id
                , comments.user_id as comment_user_id
                , comments.comment as comment_comment
                , comments.created_at as comment_created_at
            
                , users.id as user_id
                , users.account_name as user_account_name
                , users.passhash as user_passhash
                , users.authority as user_authority
                , users.del_flg as user_del_flg
                , users.created_at as user_created_at
            FROM comments
            JOIN users ON comments.user_id = users.id
            WHERE post_id IN (<post_ids>)
            ORDER BY comments.created_at DESC
        """.trimIndent())
            .bindList("post_ids", postIds)
            .map { rs, _ ->
                Comment(
                    id        = rs.getInt("comment_id"),
                    postId    = rs.getInt("comment_post_id"),
                    userId    = rs.getInt("comment_user_id"),
                    comment   = rs.getString("comment_comment"),
                    createdAt = rs.getObject("comment_created_at", OffsetDateTime::class.java),
                    user = User(
                        id          = rs.getInt("user_id"),
                        accountName = rs.getString("user_account_name"),
                        passhash    = rs.getString("user_passhash"),
                        authority   = rs.getInt("user_authority"),
                        delFlg      = rs.getInt("user_del_flg"),
                        createdAt   = rs.getObject("user_created_at", OffsetDateTime::class.java),
                    )
                )
            }
            .list()
    }.groupBy { it.postId }


    for (post in results) {
        post.commentCount = jdbi.withHandle<Int, Exception> { h ->
            h.createQuery("SELECT COUNT(*) AS count FROM comments WHERE post_id = :post_id")
                .bind("post_id", post.id)
                .mapTo<Int>()
                .findOne()
                .orElse(0)
        }

        var comments = commentsMap[post.id] ?: emptyList()
        if (!allComments) {
            val limit = if (comments.size > 3) {
                3
            } else {
                comments.size
            }
            comments = comments.subList(0, limit)
        }

        for (i in comments.indices) {
            comments[i].user = jdbi.withHandle<User, Exception> { h ->
                h.createQuery("SELECT * FROM users WHERE id = :id")
                    .bind("id", comments[i].userId)
                    .mapTo<User>()
                    .findOne()
                    .getOrNull()
            }
        }

        // reverse
        post.comments = comments.reversed()

        post.csrfToken = csrfToken

        posts.add(post)
    }

    return posts
}

private fun ApplicationCall.getSessionUser(): User? {
    val session = sessions.get<UserSession>() ?: return null
    val userId = session.userId

    val user = jdbi.withHandle<User?, Exception> { h ->
        h.createQuery("SELECT * FROM users WHERE id = :user_id")
            .bind("user_id", userId)
            .mapTo<User>()
            .findOne()
            .orElse(null)
    }

    return user
}

private fun ApplicationCall.getFlash(): String {
    val session = sessions.get<UserSession>() ?: return ""
    val notice = session.notice
    sessions.set(session.copy(notice = ""))
    return notice
}

object TemplateHelpers {
    fun imageUrl(post: Post): String {
        val ext = when (post.mime) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            else -> ""
        }
        return "/image/${post.id}$ext"
    }
}

private suspend fun RoutingContext.getInitialize() {
    dbInitialize()
    cleanupImages()
    call.respond(HttpStatusCode.OK)
}

private suspend fun RoutingContext.getLogin() {
    val me = call.getSessionUser()

    if (me != null) {
        call.respondRedirect("/")
        return
    }

    val flash = call.getFlash()

    call.respond(
        FreeMarkerContent(
            "login.ftl",
            mapOf("flash" to flash),
            ""
        )
    )
}

private suspend fun RoutingContext.postLogin() {
    if (call.getSessionUser() != null) {
        call.respondRedirect("/")
        return
    }

    val params = call.receive<Parameters>()
    val accountName = params["account_name"]
    val password = params["password"]

    val user = tryLogin(accountName, password)

    if (user != null) {
        call.sessions.set(UserSession(userId = user.id, csrfToken = secureRandomStr(16)))
        call.respondRedirect("/")
    } else {
        call.sessions.set(UserSession(notice = "アカウント名かパスワードが間違っています"))
        call.respondRedirect("/login")
    }
}

private suspend fun RoutingContext.getRegister() {
    if (call.getSessionUser() != null) {
        call.respondRedirect("/")
        return
    }

    call.respond(
        FreeMarkerContent(
            "register.ftl",
            mapOf("flash" to call.getFlash()),
        )
    )
}

private suspend fun RoutingContext.postRegister() {
    if (call.getSessionUser() != null) {
        call.respondRedirect("/")
        return
    }

    val params = call.receive<Parameters>()
    val accountName = params["account_name"]
    val password = params["password"]

    val validated  = validateUser(accountName, password)
    if (!validated) {
        call.sessions.set(UserSession(notice = "アカウント名は3文字以上、パスワードは6文字以上である必要があります"))
        call.respondRedirect("/register")
        return
    }

    val exists = jdbi.withHandle<Int, Exception> { h ->
        h.createQuery("SELECT 1 FROM users WHERE account_name = :account_name")
            .bind("account_name", accountName)
            .mapTo<Int>()
            .findOne()
            .orElse(0)
    } == 1

    if (exists) {
        call.sessions.set(UserSession(notice = "アカウント名がすでに使われています"))
        call.respondRedirect("/register")
        return
    }

    val userId = jdbi.withHandle<Int, Exception> { h ->
        h.createUpdate("INSERT INTO users (account_name, passhash) VALUES (:account_name, :passhash)")
            .bind("account_name", accountName)
            .bind("passhash", calculatePasshash(accountName!!, password!!))
            .executeAndReturnGeneratedKeys("id")
            .mapTo<Int>()
            .one()
    }

    call.sessions.set(UserSession(userId = userId, csrfToken = secureRandomStr(16)))
    call.respondRedirect("/")
}

private suspend fun RoutingContext.getLogout() {
    call.sessions.clear<UserSession>()
    call.respondRedirect("/")
}

private suspend fun RoutingContext.getIndex() {
    val me = call.getSessionUser()
    val results = jdbi.withHandle<List<Post>, Exception> { h ->
        h.createQuery("""
            SELECT
                posts.id, posts.user_id, posts.body, posts.mime, posts.created_at,
                users.id        AS u_id,
                users.account_name,
                users.passhash,
                users.authority,
                users.del_flg,
                users.created_at AS u_created_at
            FROM posts
            JOIN users ON posts.user_id = users.id
            WHERE users.del_flg = 0
            ORDER BY posts.created_at DESC
            LIMIT 20
        """.trimIndent())
            .map { rs, _ ->
                Post(
                    id        = rs.getInt("id"),
                    userId    = rs.getInt("user_id"),
                    body      = rs.getString("body"),
                    mime      = rs.getString("mime"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                    user = User(
                        id          = rs.getInt("u_id"),
                        accountName = rs.getString("account_name"),
                        passhash    = rs.getString("passhash"),
                        authority   = rs.getInt("authority"),
                        delFlg      = rs.getInt("del_flg"),
                        createdAt   = rs.getObject("u_created_at", OffsetDateTime::class.java),
                    )
                )
            }
            .list()
    }

    val posts = makePostsNew(results, call.getCsrfToken(), false)

    val flash = call.getFlash()

    call.respond(
        FreeMarkerContent(
            "index.ftl",
            mapOf(
                "posts" to posts,
                "me" to me,
                "csrf_token" to call.getCsrfToken(),
                "flash" to flash,
                "h" to TemplateHelpers,
            )
        )
    )
}

private suspend fun RoutingContext.getAccountName() {
    val accountName = call.parameters["accountName"] ?: ""

    val user = jdbi.withHandle<User?, Exception> { h ->
        h.createQuery("SELECT * FROM users WHERE account_name = :account_name AND del_flg = 0")
            .bind("account_name", accountName)
            .mapTo<User>()
            .findOne()
            .getOrNull()
    } ?: run {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val results = jdbi.withHandle<List<Post>, Exception> { h ->
        h.createQuery("""
            SELECT
                posts.id as post_id
                , posts.user_id as user_id
                , posts.body as post_body
                , posts.mime as post_mime
                , posts.created_at as post_created_at
                , users.id as user_id
                , users.account_name as user_account_name
                , users.passhash as user_passhash
                , users.authority as user_authority
                , users.del_flg as user_del_flg
                , users.created_at as user_created_at
            FROM posts
            JOIN users ON posts.user_id = users.id
            WHERE posts.user_id = :user_id
            ORDER BY posts.created_at DESC
            LIMIT 20
        """.trimIndent())
            .bind("user_id", user.id)
            .map { rs, _ ->
                Post(
                    id        = rs.getInt("post_id"),
                    userId    = rs.getInt("user_id"),
                    body      = rs.getString("post_body"),
                    mime      = rs.getString("post_mime"),
                    createdAt = rs.getObject("post_created_at", OffsetDateTime::class.java),
                    user = User(
                        id          = rs.getInt("user_id"),
                        accountName = rs.getString("user_account_name"),
                        passhash    = rs.getString("user_passhash"),
                        authority   = rs.getInt("user_authority"),
                        delFlg      = rs.getInt("user_del_flg"),
                        createdAt   = rs.getObject("user_created_at", OffsetDateTime::class.java),
                    )
                )
            }
            .list()
    }

    val posts = makePostsNew(results, call.getCsrfToken(), false)

    val commentCount = jdbi.withHandle<Int, Exception> { h ->
        h.createQuery("SELECT COUNT(*) AS count FROM comments WHERE user_id = :user_id")
            .bind("user_id", user.id)
            .mapTo<Int>()
            .findOne()
            .getOrElse({ 0 })
    }

    val postIds = jdbi.withHandle<List<Int>, Exception> { h ->
        h.createQuery("SELECT id FROM posts WHERE user_id = :user_id")
            .bind("user_id", user.id)
            .mapTo<Int>()
            .list()
    }
    val postCount = postIds.size

    val commentedCount = if (postCount > 0) {
        jdbi.withHandle<Int, Exception> { h ->
            h.createQuery("SELECT COUNT(*) AS count FROM `comments` WHERE `post_id` IN (<post_ids>)")
                .bindList("post_ids", postIds)
                .mapTo<Int>()
                .one()
        }
    } else 0

    val me = call.getSessionUser()

    call.respond(
        FreeMarkerContent(
            "user.ftl",
            mapOf(
                "posts" to posts,
                "user" to user,
                "post_count" to postCount,
                "comment_count" to commentCount,
                "commented_count" to commentedCount,
                "me" to me,
                "h" to TemplateHelpers,
                ),
        )
    )
}

private suspend fun RoutingContext.getPosts() {
    val maxCreatedAt = call.request.queryParameters["max_created_at"]
    if (maxCreatedAt == null) {
        call.respond(HttpStatusCode.OK)
        return
    }

    val t: OffsetDateTime = try {
        OffsetDateTime.parse(maxCreatedAt)
    } catch (e: DateTimeParseException) {
        call.application.log.warn(e.message)
        call.respond(HttpStatusCode.OK)
        return
    }

    val results = jdbi.withHandle<List<Post>, Exception> { h ->
        h.createQuery("""
            SELECT
                posts.id as post_id
                , posts.user_id as post_user_id
                , posts.body as post_body
                , posts.mime as post_mime
                , posts.created_at as post_created_at

                , users.id as user_id
                , users.account_name as user_account_name
                , users.passhash as user_passhash
                , users.authority as user_authority
                , users.del_flg as user_del_flg
                , users.created_at as user_created_at
            FROM
                posts
            JOIN
                users
                ON posts.user_id = users.id
            WHERE
                posts.created_at <= :created_at
                AND users.del_flg = 0
            ORDER BY
                posts.created_at DESC
            LIMIT
                20
        """.trimIndent())
            .bind("created_at", t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .map { rs, _ ->
                Post(
                    id        = rs.getInt("post_id"),
                    userId    = rs.getInt("user_id"),
                    body      = rs.getString("post_body"),
                    mime      = rs.getString("post_mime"),
                    createdAt = rs.getObject("post_created_at", OffsetDateTime::class.java),
                    user = User(
                        id          = rs.getInt("user_id"),
                        accountName = rs.getString("user_account_name"),
                        passhash    = rs.getString("user_passhash"),
                        authority   = rs.getInt("user_authority"),
                        delFlg      = rs.getInt("user_del_flg"),
                        createdAt   = rs.getObject("user_created_at", OffsetDateTime::class.java),
                    )
                )
            }
            .list()
    }

    val posts = makePostsNew(results, call.getCsrfToken(), false)

    if (posts.isEmpty()) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    call.respond(
        FreeMarkerContent(
            "posts.ftl",
            mapOf(
                "posts" to posts,
                "h" to TemplateHelpers,
            )
        )
    )
}

private suspend fun RoutingContext.getPostsId() {
    val pid = call.parameters["id"]?.toIntOrNull()
    if (pid == null) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val results = jdbi.withHandle<List<Post>, Exception> { h ->
        h.createQuery("""
            SELECT
                posts.id as post_id
                , posts.user_id as post_user_id
                , posts.body as post_body
                , posts.mime as post_mime
                , posts.created_at as post_created_at
            
                , users.id as user_id
                , users.account_name as user_account_name
                , users.passhash as user_passhash
                , users.authority as user_authority
                , users.del_flg as user_del_flg
                , users.created_at as user_created_at
            FROM posts
            JOIN users ON posts.user_id = users.id
            WHERE posts.id = :post_id
            AND users.del_flg = 0
        """.trimIndent())
            .bind("post_id", pid)
            .map { rs, _ ->
                Post(
                    id        = rs.getInt("post_id"),
                    userId    = rs.getInt("user_id"),
                    body      = rs.getString("post_body"),
                    mime      = rs.getString("post_mime"),
                    createdAt = rs.getObject("post_created_at", OffsetDateTime::class.java),
                    user = User(
                        id          = rs.getInt("user_id"),
                        accountName = rs.getString("user_account_name"),
                        passhash    = rs.getString("user_passhash"),
                        authority   = rs.getInt("user_authority"),
                        delFlg      = rs.getInt("user_del_flg"),
                        createdAt   = rs.getObject("user_created_at", OffsetDateTime::class.java),
                    )
                )
            }
            .list()
    }

    val posts = makePostsNew(results, call.getCsrfToken(), true)

    if (posts.isEmpty()) {
        call.respond(HttpStatusCode.NotFound)
        return
    }

    val post = posts.first()

    val me = call.getSessionUser()

    call.respond(
        FreeMarkerContent(
            "post_id.ftl",
            mapOf(
                "post" to post,
                "me" to me,
                "h" to TemplateHelpers,
            )
        )
    )
}

private suspend fun RoutingContext.postIndex() {
    val me = call.getSessionUser()
    if (me == null) {
        call.respondRedirect("/login")
        return
    }

    val multipart = call.receiveMultipart()
    var csrfTokenParam: String? = null
    var bodyParam: String? = null
    var fileBytes: ByteArray? = null
    var fileContentType: String? = null

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> when (part.name) {
                "csrf_token" -> csrfTokenParam = part.value
                "body" -> bodyParam = part.value
            }
            is PartData.FileItem -> if (part.name == "file") {
                fileContentType = part.contentType?.toString()
                fileBytes = part.streamProvider().readBytes()
            }
            else -> {}
        }
        part.dispose()
    }

    if (csrfTokenParam != call.getCsrfToken()) {
        call.respond(HttpStatusCode.UnprocessableEntity)
        return
    }

    if (fileBytes == null) {
        val session = call.sessions.get<UserSession>() ?: UserSession()
        call.sessions.set(session.copy(notice = "画像が必須です"))
        call.respondRedirect("/")
        return
    }

    val (mime, ext) = when {
        fileContentType?.contains("jpeg") == true -> "image/jpeg" to "jpg"
        fileContentType?.contains("png") == true -> "image/png" to "png"
        fileContentType?.contains("gif") == true -> "image/gif" to "gif"
        else -> {
            val session = call.sessions.get<UserSession>() ?: UserSession()
            call.sessions.set(session.copy(notice = "投稿できる画像形式はjpgとpngとgifだけです"))
            call.respondRedirect("/")
            return
        }
    }

    if (fileBytes.size > UploadLimit) {
        val session = call.sessions.get<UserSession>() ?: UserSession()
        call.sessions.set(session.copy(notice = "ファイルサイズが大きすぎます"))
        call.respondRedirect("/")
        return
    }

    val emptyFileByte = ByteArray(0)
    val pid = jdbi.withHandle<Int, Exception> { h ->
        h.createUpdate("INSERT INTO posts (user_id, mime, imgdata, body) VALUES (:user_id,:mime,:imgdata,:body)")
            .bind("user_id", me.id)
            .bind("mime", mime)
            .bind("imgdata", emptyFileByte)
            .bind("body", bodyParam)
            .executeAndReturnGeneratedKeys("id")
            .mapTo<Int>()
            .one()
    }

    // アップロードされたファイルを配信ディレクトリに書き出し
    val imgFile = "${ImageDir}/${pid}.${ext}"
    // 例: /home/isucon/private_isu/webapp/public/image/〇〇.png
    File(imgFile).writeBytes(fileBytes)
    call.respondRedirect("/posts/${pid}")
}

private suspend fun RoutingContext.postComment() {
    val me = call.getSessionUser()
    if (me == null) {
        call.respondRedirect("/login")
        return
    }

    val params = call.receive<Parameters>()

    if (params["csrf_token"] != call.getCsrfToken()) {
        call.respond(HttpStatusCode.UnprocessableEntity)
        return
    }

    val postId = params["post_id"]?.toIntOrNull()
    if (postId == null) {
        call.application.log.warn("post_idは整数のみです")
        call.respond(HttpStatusCode.OK)
        return
    }

    jdbi.withHandle<Unit, Exception> { h ->
        h.createUpdate("INSERT INTO comments (post_id, user_id, comment) VALUES (:post_id, :user_id, :comment)")
            .bind("post_id", postId)
            .bind("user_id", me.id)
            .bind("comment", params["comment"])
            .execute()
    }

    call.respondRedirect("/posts/$postId")
}

private suspend fun RoutingContext.getAdminBanned() {
    val me = call.getSessionUser()

    if (me == null) {
        call.respondRedirect("/")
        return
    }

    if (me.authority == 0) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val users = jdbi.withHandle<List<User>, Exception> { h ->
        h.createQuery("SELECT * FROM users WHERE authority = 0 AND del_flg = 0 ORDER BY created_at DESC")
            .mapTo<User>()
            .list()
    }

    call.respond(
        FreeMarkerContent(
            "banned.ftl",
            mapOf(
                "users" to users,
                "me" to me,
                "csrf_token" to call.getCsrfToken(),
            )
        )
    )
}

private suspend fun RoutingContext.postAdminBanned() {
    val me = call.getSessionUser()

    if (me == null) {
        call.respondRedirect("/")
        return
    }

    if (me.authority == 0) {
        call.respond(HttpStatusCode.Forbidden)
        return
    }

    val params = call.receive<Parameters>()

    if (params["csrf_token"] != call.getCsrfToken()) {
        call.respond(HttpStatusCode.UnprocessableEntity)
        return
    }

    val uids = params.getAll("uid[]") ?: emptyList()
    jdbi.useHandle<Exception> { h ->
        for (id in uids) {
            h.createUpdate("UPDATE users SET del_flg = 1 WHERE id = :id")
                .bind("id", id)
                .execute()
        }
    }

    call.respondRedirect("/admin/banned")
}

fun Application.configureRouting() {
    routing {
        get("/initialize") { getInitialize() }
        get("/login") { getLogin() }
        post("/login") { postLogin() }
        get("/register") { getRegister() }
        post("/register") { postRegister() }
        get("/logout") { getLogout() }
        get("/") { getIndex() }
        get("/posts") { getPosts() }
        get("/posts/{id}") { getPostsId() }
        post("/") { postIndex() }
        post("/comment") { postComment() }
        get("/admin/banned") { getAdminBanned() }
        post("/admin/banned") { postAdminBanned() }
        get(Regex("""/@(?<accountName>[0-9a-zA-Z_]+)""")) { getAccountName() }

        staticFiles("/", File("../public"))
    }
}
