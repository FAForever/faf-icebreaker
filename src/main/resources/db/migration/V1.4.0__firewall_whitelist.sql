create table firewall_whitelist
(
    id         bigint auto_increment primary key,
    user_id    bigint                                 not null,
    session_id varchar(100)                           not null,
    allowed_ip varchar(45)                            not null,
    created_at datetime default now()                 not null,
    deleted_at datetime                               null,
    /* We use a sentinel value for deleted_at because "null != null" */
    deleted_at_or_sentinel datetime as (coalesce(deleted_at, TIMESTAMP'9999-12-31 00:00:00')) stored invisible,
    index idx_firewall_whitelist_session_id (session_id),
    index idx_firewall_whitelist_user_id (user_id),
    index idx_firewall_whitelist_active (deleted_at),
    unique key unique_active_session_user (session_id, user_id, deleted_at_or_sentinel)

)
    comment 'IPs that should be whitelisted in the firewall that protects our TURN servers.';
