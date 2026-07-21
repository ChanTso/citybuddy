#!/bin/sh
set -eu

acl_file="/tmp/citybuddy-support-users.acl"
umask 077
{
  printf 'user default on >%s ~* +@all\n' "$REDIS_SUPPORT_PASSWORD"
  printf '%s\n' "user agent_cache on >$REDIS_AGENT_CACHE_PASSWORD %RW~cb:faq:v1:query:* %R~cb:faq:v1:state:* %R~cb:faq:v1:answer:* +eval +exists +hlen +hget +hgetall +hset +del +pexpire +pttl"
  printf '%s\n' "user knowledge_indexer on >$REDIS_INDEXER_CACHE_PASSWORD %RW~cb:faq:v1:state:* %RW~cb:faq:v1:answer:* +eval +exists +type +hlen +hget +hset +del +pexpire +pttl +time"
} >"$acl_file"

if [ "${1:-}" = "redis-server" ]; then
  shift
fi
exec redis-server --aclfile "$acl_file" "$@"
