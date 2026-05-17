<#import "layout.ftl" as l>
<@l.layout>
<div class="isu-submit">
    <form method="post" action="/" enctype="multipart/form-data">
        <div class="isu-form">
            <input type="file" name="file" value="file">
        </div>
        <div class="isu-form">
            <textarea name="body"></textarea>
        </div>
        <div class="form-submit">
            <input type="hidden" name="csrf_token" value="${csrf_token}">
            <input type="submit" name="submit" value="submit">
        </div>
        <#if flash?? && flash != "">
            <div id="notice-message" class="alert alert-danger">
                ${flash}
            </div>
        </#if>
    </form>
</div>

<#include "posts.ftl">

<div id="isu-post-more">
    <button id="isu-post-more-btn">もっと見る</button>
    <img src="/img/ajax-loader.gif" class="isu-loading-icon">
</div>
</@l.layout>
