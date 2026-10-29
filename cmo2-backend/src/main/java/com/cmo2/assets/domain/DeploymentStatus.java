package com.cmo2.assets.domain;

/** UNCONFIRMED: the platform never answered, so the post may or may not exist there. */
public enum DeploymentStatus {
    PENDING,
    ACCEPTED,
    FAILED,
    UNCONFIRMED
}
