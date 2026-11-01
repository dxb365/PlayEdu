# 免费证书自动续期

适用于已有群晖教学部署或首次部署。2026-11-01 查询发现 5927.fun 的权威 DNS 为 dns7.hichina.com / dns8.hichina.com，因此本版用阿里云 DNS 验证申请免费的 Let’s Encrypt 证书。无需开放 80/443，不用重复申请或下载证书。网站仍使用 8443/9443。

## 一次性设置

1. 下载并解压新的自动续期部署包。保留已有的 .env、mysql、storage 和 certs，不能用示例配置覆盖密码。将新版 compose.yaml 和 gateway.conf.template 放入原教学项目目录。导入包里的新版 playedu-teaching-gateway:local 和 playedu-teaching-acme:local 映像；首次部署可导入完整 images.tar.gz。
2. 在 File Station 的 docker/playedu-teaching 里新建 acme-config、acme-state 和 certs 文件夹，已有的保留。
3. 登录 [阿里云 RAM 访问控制](https://ram.console.aliyun.com/)，创建专用用户 playedu-certificate，仅开启程序访问的 AccessKey，不需要群晖密码。
4. 在 RAM 的权限策略中创建自定义策略，使用本包 aliyun-dns-policy.json 的 JSON 内容，名称可填 PlayEduCertificateDNS。给上一步用户添加此策略。它只允许对 5927.fun 查询、添加和删除 DNS 记录，不能管理其他域名或云服务器。阿里云当前将权限细分到域名，不能声称仅限单条 TXT；程序只为 _acme-challenge.www.5927.fun 添加/清理临时 TXT，不修改已有 A/AAAA/DDNS。
5. 为此专用 RAM 用户创建 AccessKey，保存 ID 和 Secret。在电脑浏览器中打开本包 setup-auto-certificate.html，填写联系邮箱和这两个值，点击生成 acme.env。内容只在本机生成，没有联网请求。不要把密钥、acme.env、.env 或私钥发送到聊天/GitHub。
6. 把 acme.env 上传到群晖的 docker/playedu-teaching/acme-config 文件夹，保持原 .env 不变。文件名不能带 .txt。访问权限仅授予你自己的管理账号。
7. 在 Container Manager 新增/更新原教学项目，使用新的 compose.yaml；已有项目需重新创建相关容器以使用新映像，保留全部绑定数据文件夹。首次申请通常需等待 DNS 生效，程序默认等 3 分钟再验证，CA 签发与网络状况会影响总时间。
8. 在 Container Manager 查看 certificate-manager 与 gateway 的日志：出现 Certificate check completed / Validated certificate loaded 代表检查/加载成功。查看 certs/acme-status.txt 可读到最近结果。用 https://www.5927.fun:8443 和安卓 App 验证，浏览器证书的颁发者与到期日应更新。

## 自动执行什么

专用证书容器每 6 小时检查一次，由 acme.sh 判断续期时间；失败约 1 小时后重试。私有账户与续期状态保存在 acme-state，重启群晖后继续使用。签发后校验域名、有效期、证书链和私钥配对，通过后才发布为 certs/current。教学网关只读证书目录，检测有效变化后平滑重载，不挂载 Docker 控制接口。

失败时保留已经在用的证书并重试，不能保证在断网、密钥失效或域名改变时仍能续期。证书容器连续未拿到有效证书或剩余不足 7 天时会显示健康异常，应检查日志。acme-state/last-run.log 是保存在 NAS 上的私有诊断日志，不要直接公开。

首次没有证书时，网关等待签发成功后才启动 HTTPS；有仍然有效的旧版 certs/tls.crt、tls.key 时可在等待期间继续使用。自动签发后文件由程序管理，不再手工改名。

此功能只管理教学网站 8443/9443，不自动替换 DSM 5001 的证书。原 5000/5001 服务和其他容器保留。网站自动续期不代表 NAS 的管理入口证书也已续期。

## 常见情况

- 密钥填错或权限不足：修改 acme-config/acme.env，程序下一次检查重新读取文件。也可仅重启 certificate-manager 立即重试，不需要重置数据库。
- 等待证书：确认阿里云云解析里能看到 5927.fun；如果实际权威 DNS 以后变成 DNSPod，需切换对应 DNS 接口，不能继续用阿里云密钥。
- 已存在 _acme-challenge 的 CNAME 或 CAA 限制：需检查是否允许 letsencrypt.org 签发；保留原网站解析，不要随意删除其他记录。
- 当前仅准备好部署包：必须在你的 NAS 填入密钥并完成首次签发、网站和视频实播，才能确认已启用自动续期。

## 备份

在原 mysql、storage、.env、certs 之外，同时备份 acme-config 和 acme-state，单独保存在你控制的位置。不要重复生成数据库密码。账户与密钥失效后需在阿里云重新创建/授权，不能通过代码绕过。

## 依据

- [Let’s Encrypt DNS-01](https://letsencrypt.org/docs/challenge-types/#dns-01-challenge)
- [acme.sh 3.1.6](https://github.com/acmesh-official/acme.sh/tree/3.1.6)
- [阿里云 DNS 自定义权限](https://help.aliyun.com/zh/dns/alibaba-cloud-dns-custom-permission-policy-reference-dns)
