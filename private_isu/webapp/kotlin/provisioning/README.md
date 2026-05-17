# 競技用インスタンスのセットアップ方法

`catatsuy/private-isu` の AMI をベースに、isu-ruby から isu-kotlin へ切り替えるための Ansible playbook。

## 事前準備

### 1. EC2 インスタンスの用意

https://github.com/catatsuy/private-isu に記載されている IAM を利用して、EC2 インスタンスを起動してください。

### 2. hosts ファイルの作成

`hosts.tmpl` をコピーして `hosts` を作成し、実際の値に書き換えます。

```sh
cp provisioning/hosts{.tmpl,}
```

`provisioning/hosts` を開き、以下のプレースホルダを置き換えます。

| プレースホルダ         | 置き換える値                                |
| ---------------------- | ------------------------------------------- |
| `{{PUBLIC_IP}}`        | EC2 インスタンスのパブリック IP アドレス    |
| `{{REPLACE_KEY_PATH}}` | SSH 秘密鍵のパス（例: `~/.ssh/my-key.pem`） |

## 実行

リポジトリルートから実行します。
private-isu の EC2 インスタンスに、kotlin 実装がデプロイされます。

```sh
ansible-playbook -i provisioning/hosts provisioning/image/ansible/playbooks.yml
```
