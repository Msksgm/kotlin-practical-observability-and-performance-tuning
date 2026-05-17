<div class="isu-post" id="pid_${post.id}" data-created-at="${post.createdAt}">
    <div class="isu-post-header">
        <a href="/@${post.user.accountName}" class="isu-post-account-name">${post.user.accountName}</a>
        <a href="/posts/${post.id}" class="isu-post-permalink">
            <time class="timeago" datetime="${post.createdAt}"></time>
        </a>
    </div>
    <div class="isu-post-image">
        <img src="${h.imageUrl(post)}" class="isu-image">
    </div>
    <div class="isu-post-text">
        <a href="/@${post.user.accountName}" class="isu-post-account-name">${post.user.accountName}</a>
        ${post.body}
    </div>
    <div class="isu-post-comment">
        <div class="isu-post-comment-count">
            comments: <b>${post.commentCount}</b>
        </div>
        <#list post.comments as comment>
            <div class="isu-comment">
                <a href="/@${comment.user.accountName}" class="isu-comment-account-name">${comment.user.accountName}</a>
                <span class="isu-comment-text">${comment.comment}</span>
            </div>
        </#list>
        <div class="isu-comment-form">
            <form method="post" action="/comment">
                <input type="text" name="comment">
                <input type="hidden" name="post_id" value="${post.id}">
                <input type="hidden" name="csrf_token" value="${post.csrfToken}">
                <input type="submit" name="submit" value="submit">
            </form>
        </div>
    </div>
</div>
