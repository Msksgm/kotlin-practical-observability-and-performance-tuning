<#macro layout>
    <!DOCTYPE html>
    <html>
        <head>
            <meta charset="utf-8">
            <title>Iscogram</title>
            <link href="/css/style.css" media="screen" rel="stylesheet" type="text/css">
        </head>
        <body>
            <div class="container">
                <div class="header">
                    <div class="isu-title">
                        <h1><a href="/">Iscogram</a></h1>
                    </div>
                    <div class="isu-header-menu">
                        <#if me?? && me.id != 0>
                            <div><a href="/@${me.accountName}"><span class="isu-account-name">${me.accountName}</span>さん</a></div>
                            <#if me.authority == 1>
                                <div><a href="/admin/banned">管理者用ページ</a></div>
                            </#if>
                            <div><a href="/logout">ログアウト</a></div>
                        <#else>
                            <div><a href="/login">ログイン</a></div>
                        </#if>
                    </div>
                </div>
                <#nested>
            </div>
            <script src="/js/timeago.min.js"></script>
            <script src="/js/main.js"></script>
        </body>
    </html>
</#macro>