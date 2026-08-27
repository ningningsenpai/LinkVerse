#!/usr/bin/env bash
set -Eeuo pipefail

required_variables=(
  MYSQL_ROOT_PASSWORD
  IDENTITY_DB_NAME IDENTITY_APP_USER IDENTITY_APP_PASSWORD IDENTITY_MIGRATOR_USER IDENTITY_MIGRATOR_PASSWORD
  TRADE_DB_NAME TRADE_APP_USER TRADE_APP_PASSWORD TRADE_MIGRATOR_USER TRADE_MIGRATOR_PASSWORD
  PAYMENT_DB_NAME PAYMENT_APP_USER PAYMENT_APP_PASSWORD PAYMENT_MIGRATOR_USER PAYMENT_MIGRATOR_PASSWORD
)

for variable_name in "${required_variables[@]}"; do
  if [[ -z "${!variable_name:-}" ]]; then
    echo "缺少数据库初始化变量：${variable_name}" >&2
    exit 1
  fi
done

for database_name in "${IDENTITY_DB_NAME}" "${TRADE_DB_NAME}" "${PAYMENT_DB_NAME}"; do
  if [[ ! "${database_name}" =~ ^[a-z0-9_]+$ ]]; then
    echo "数据库名称只能包含小写字母、数字和下划线：${database_name}" >&2
    exit 1
  fi
done

for account_name in \
  "${IDENTITY_APP_USER}" "${IDENTITY_MIGRATOR_USER}" \
  "${TRADE_APP_USER}" "${TRADE_MIGRATOR_USER}" \
  "${PAYMENT_APP_USER}" "${PAYMENT_MIGRATOR_USER}"; do
  if [[ "${account_name}" != 'root' ]]; then
    echo "本地 MVP 数据库账号必须统一为 root：${account_name}" >&2
    exit 1
  fi
done

for password in \
  "${MYSQL_ROOT_PASSWORD}" \
  "${IDENTITY_APP_PASSWORD}" "${IDENTITY_MIGRATOR_PASSWORD}" \
  "${TRADE_APP_PASSWORD}" "${TRADE_MIGRATOR_PASSWORD}" \
  "${PAYMENT_APP_PASSWORD}" "${PAYMENT_MIGRATOR_PASSWORD}"; do
  if [[ ! "${password}" =~ ^[A-Za-z0-9_@%+=:,.-]{8,128}$ ]]; then
    echo "本地数据库密码必须为 8～128 位安全字符，且不得包含引号、空格或反斜杠。" >&2
    exit 1
  fi
  if [[ "${password}" != "${MYSQL_ROOT_PASSWORD}" ]]; then
    echo "本地数据库运行与迁移密码必须和 root 密码一致。" >&2
    exit 1
  fi
done

# 宿主机经当前 Compose 网桥访问时，仅向该网段开放三个业务 Schema。
MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket --user=root <<SQL
CREATE DATABASE IF NOT EXISTS ${IDENTITY_DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS ${TRADE_DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS ${PAYMENT_DB_NAME} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'root'@'172.18.%' IDENTIFIED BY '${MYSQL_ROOT_PASSWORD}';
ALTER USER 'root'@'172.18.%' IDENTIFIED BY '${MYSQL_ROOT_PASSWORD}';
GRANT ALL PRIVILEGES ON ${IDENTITY_DB_NAME}.* TO 'root'@'172.18.%';
GRANT ALL PRIVILEGES ON ${TRADE_DB_NAME}.* TO 'root'@'172.18.%';
GRANT ALL PRIVILEGES ON ${PAYMENT_DB_NAME}.* TO 'root'@'172.18.%';

DROP USER IF EXISTS 'root'@'%';
DROP USER IF EXISTS 'linkverse_mvp_identity_app'@'%';
DROP USER IF EXISTS 'linkverse_mvp_identity_migrator'@'%';
DROP USER IF EXISTS 'linkverse_mvp_trade_app'@'%';
DROP USER IF EXISTS 'linkverse_mvp_trade_migrator'@'%';
DROP USER IF EXISTS 'linkverse_mvp_payment_app'@'%';
DROP USER IF EXISTS 'linkverse_mvp_payment_migrator'@'%';
FLUSH PRIVILEGES;
SQL
