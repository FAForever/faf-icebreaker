alter table turn_servers
    add hetzner_firewall boolean default false not null
        comment 'Whether this TURN server sits behind the Hetzner cloud firewall and needs IP whitelisting';
