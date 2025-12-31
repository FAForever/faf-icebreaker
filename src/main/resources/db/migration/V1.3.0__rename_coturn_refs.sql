rename table coturn_servers to turn_servers;

alter table turn_servers
    comment = 'List of TURN servers to be propagated to ICE adapters';

alter table turn_servers
    modify preshared_key varchar(255) not null comment 'A preshared key for hmac verification (TURN long-term credentials).';
