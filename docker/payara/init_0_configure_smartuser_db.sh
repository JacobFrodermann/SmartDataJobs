#!/bin/bash
#
# Runs before init_1_generate_deploy_commands.sh.
#
# The SmartUser application expects the JTA data source jdbc/SmartUser. It is
# created here before Payara appends its automatic deployment commands to the
# post-boot command file.
#
# This file is sourced by the Payara container entrypoint, so use return
# instead of exit when skipping the configuration.

DB_HOST="${SMARTUSER_DB_HOST:-smartuser-db}"
DB_PORT="${SMARTUSER_DB_PORT:-5432}"
DB_NAME="${SMARTUSER_DB_NAME:-smartuser}"
DB_USER="${SMARTUSER_DB_USER:-smartuser}"
DB_PASSWORD="${SMARTUSER_DB_PASSWORD:-smartuser}"

DOMAIN_XML="${PAYARA_DIR}/glassfish/domains/${DOMAIN_NAME}/config/domain.xml"

# Do not add duplicate resources when an existing domain is restarted.
if [ -f "$DOMAIN_XML" ] && grep -q 'jdbc/SmartUser' "$DOMAIN_XML"; then
    return 0 2>/dev/null || exit 0
fi

cat >> "$POSTBOOT_COMMANDS" <<EOF
create-jdbc-connection-pool --datasourceclassname org.postgresql.ds.PGSimpleDataSource --restype javax.sql.DataSource --property user=${DB_USER}:password=${DB_PASSWORD}:url=jdbc\\:postgresql\\://${DB_HOST}\\:${DB_PORT}/${DB_NAME}:driverClass=org.postgresql.Driver SmartUserPool
create-jdbc-resource --connectionpoolid SmartUserPool jdbc/SmartUser

EOF
