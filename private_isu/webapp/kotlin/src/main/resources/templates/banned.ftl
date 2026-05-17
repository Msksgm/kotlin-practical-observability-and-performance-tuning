<#import "layout.ftl" as l>
<@l.layout>
    <div>
        <form method="post" action="/admin/banned">
            <#list users as user>
                <div>
                    <input type="checkbox" name="uid[]" id="uid_${user.id}" value="${user.id}" data-account-name="${user.accountName}">
                    <label for="uid_${user.id}">${user.accountName}</label>
                </div>
            </#list>
            <div class="form-submit">
                <input type="hidden" name="csrf_token" value="${csrf_token}">
                <input type="submit" name="submit" value="submit">
            </div>
        </form>
    </div>
</@l.layout>
