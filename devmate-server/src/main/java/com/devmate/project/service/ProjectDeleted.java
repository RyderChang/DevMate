package com.devmate.project.service;

/** Synchronous notification inside the project deletion transaction; listeners must persist their handoff. */
public record ProjectDeleted(long ownerUserId, long projectId) {}
