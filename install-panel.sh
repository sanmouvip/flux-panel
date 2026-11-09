#!/usr/bin/env bash
set -euo pipefail
umask 077

version="v1.0.0"
install_dir="$PWD/flux-panel"
compose_file="docker-compose-v4.yml"

die() {
    printf '%s\n' "$*" >&2
    exit 1
}

case "${1:-}" in
    "") ;;
    --ipv6) compose_file="docker-compose-v6.yml" ;;
    *) die "用法：bash install-panel.sh [--ipv6]" ;;
esac

for command in curl tar openssl docker; do
    command -v "$command" >/dev/null || die "请先安装 $command。"
done
docker compose version >/dev/null || die "请先安装 Docker Compose 插件。"
docker info >/dev/null || die "无法连接 Docker，请确认 Docker 已启动并有操作权限。"

[[ ! -e "$install_dir" && ! -L "$install_dir" ]] || die "目录已存在：$install_dir。请按 README 的说明继续部署或升级。"
volumes=$(docker volume ls --format '{{.Name}}')
if grep -Fxq 'mysql_data' <<< "$volumes"; then
    die "此 Docker 主机已有 mysql_data 数据卷。请按 README 的升级说明保留旧数据。"
fi
containers=$(docker ps -a --format '{{.Names}}')
if grep -Eq '^(gost-mysql|gost-phpmyadmin|springboot-backend|vite-frontend)$' <<< "$containers"; then
    die "此 Docker 主机已有同名面板容器。请按 README 的升级说明操作。"
fi

staging=$(mktemp -d "$PWD/.flux-panel.XXXXXX")
trap 'rm -rf -- "$staging"' EXIT
mkdir "$staging/source"

printf '正在下载 Flux Panel %s 源码…\n' "$version"
curl --fail --silent --show-error --location --retry 3 \
    "https://codeload.github.com/sanmouvip/flux-panel/tar.gz/refs/tags/$version" \
    -o "$staging/source.tar.gz"
tar -xzf "$staging/source.tar.gz" --strip-components=1 -C "$staging/source"

db_password=$(openssl rand -hex 24)
jwt_secret=$(openssl rand -hex 32)
cat > "$staging/source/.env" <<EOF
DB_NAME=gost
DB_USER=gost_panel
DB_PASSWORD=$db_password
JWT_SECRET=$jwt_secret
BACKEND_PORT=6365
FRONTEND_PORT=6366
EOF

mv "$staging/source" "$install_dir"
cd "$install_dir"
printf '正在构建并启动面板…\n'
if ! docker compose -f "$compose_file" -f docker-compose.source.yml \
    up -d --build --scale phpmyadmin=0 --wait --wait-timeout 240; then
    printf '启动失败。已保留部署文件，可修复报错后在 %s 继续部署。\n' "$install_dir" >&2
    printf '重试命令：docker compose -f %s -f docker-compose.source.yml up -d --build --scale phpmyadmin=0 --wait --wait-timeout 240\n' "$compose_file" >&2
    exit 1
fi
printf '面板已启动：访问 http://服务器IP:6366\n'
printf '节点上报端口：6365。首次登录后请立即修改默认管理员密码。\n'
