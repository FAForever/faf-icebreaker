create table coturn_servers
(
    id            int(11) unsigned       not null auto_increment,
    region        varchar(20)            not null comment 'Region of the server for simple user-based selection if latency checks don''t work',
    host          varchar(255)           not null,
    port          mediumint default 3478 not null,
    preshared_key varchar(255)           not null comment 'A preshared key for hmac verification (coturn long-term credentials).',
    contact_email varchar(255)           not null comment 'Email of the responsible server administrator',
    active        boolean default true   not null,
    PRIMARY KEY(id),
    UNIQUE KEY `unique_host_port` (`host`, `port`)
)
    comment 'List of coturn servers to be propagated to ICE adapters';

create table ice_sessions
(
    id            varchar(36)            not null,
    game_id       int(11) unsigned       not null,
    created_at    datetime default now() not null,
    PRIMARY KEY(id),
    UNIQUE KEY `unique_game_id` (`game_id`)
)
    comment 'List of ICE sessions (mostly relevant for managed services like Xirsys)';
