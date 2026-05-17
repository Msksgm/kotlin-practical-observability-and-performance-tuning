<#import "layout.ftl" as l>
<@l.layout>
    <div class="isu-user">
        <div><span class="isu-user-account-name">${user.accountName}さん</span>のページ</div>
        <div>投稿数<span class="isu-post-count">${post_count}</span></div>
        <div>コメント数<span class="isu-comment-count">${comment_count}</span></div>
        <div>被コメント数 <span class="isu-commented-count">${commented_count}</span></div>
    </div>
    <#include "posts.ftl">
</@l.layout>
