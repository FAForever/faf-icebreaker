alter table firewall_whitelist
    add column address_family enum ('IPV4', 'IPV6') not null default 'IPV4' after allowed_ip;

update firewall_whitelist
set address_family = 'IPV6'
where instr(allowed_ip, ':') > 0;

alter table firewall_whitelist
    add unique key unique_active_session_user_family
        (session_id, user_id, address_family, deleted_at_or_sentinel),
    drop index unique_active_session_user;

alter table firewall_whitelist
    alter column address_family drop default;
