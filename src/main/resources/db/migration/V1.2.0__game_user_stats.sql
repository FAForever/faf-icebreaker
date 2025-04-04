create table game_user_stats
(
    id                  int auto_increment
        primary key,
    game_id             int(11) unsigned                       null,
    user_id             int(11) unsigned                       null,
    connection_attempts int                                    not null,
    log_bytes_pushed    int                                    not null,
    connectivity_status json                                   not null,
    created_at          datetime default now()                 not null,
    updated_at          datetime default now() on update now() not null,
    constraint session_user_stats_unique_game_user
        unique (user_id, game_id)
);

alter table game_user_stats
    add constraint game_user_stats_ice_sessions_game_id_fk
        foreign key (game_id) references ice_sessions (game_id);

