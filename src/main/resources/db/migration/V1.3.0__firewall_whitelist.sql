create table firewall_whitelist
(
    id         bigint auto_increment primary key,
    user_id    bigint                                 not null,
    session_id varchar(100)                           not null,
    allowed_ip varchar(45)                            not null,
    created_at datetime default now()                 not null,
    deleted_at datetime                               null,
    index idx_firewall_whitelist_session_id (session_id),
    index idx_firewall_whitelist_user_id (user_id),
    index idx_firewall_whitelist_active (deleted_at)
)
    comment 'IPs that should be whitelisted in the firewall that protects our TURN servers.';
