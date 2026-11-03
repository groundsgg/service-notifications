ALTER TABLE moderation_notification_projection ADD COLUMN navigation_report_id UUID;

CREATE TABLE moderation_case_navigation_state (
    environment TEXT NOT NULL CHECK(environment IN ('stage','prod')),
    case_id UUID NOT NULL,
    last_case_revision BIGINT NOT NULL CHECK(last_case_revision>0),
    structure_sequence BIGINT NOT NULL DEFAULT 0 CHECK(structure_sequence>=0),
    structure_revision BIGINT NOT NULL DEFAULT 0 CHECK(structure_revision>=0),
    PRIMARY KEY(environment,case_id)
);
CREATE TABLE moderation_structure_operation (
    environment TEXT NOT NULL CHECK(environment IN ('stage','prod')),
    operation_id UUID NOT NULL,
    structure_sequence BIGINT NOT NULL CHECK(structure_sequence BETWEEN 1 AND 9007199254740991),
    header_hash BYTEA NOT NULL CHECK(octet_length(header_hash)=32),
    chunk_count INTEGER NOT NULL CHECK(chunk_count>0),
    result_json JSONB,
    finalized_at TIMESTAMPTZ,
    PRIMARY KEY(environment,operation_id),
    UNIQUE(environment,structure_sequence),
    CHECK((result_json IS NULL)=(finalized_at IS NULL))
);
CREATE TABLE moderation_structure_chunk (
    environment TEXT NOT NULL,
    operation_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL CHECK(chunk_index>=0),
    semantic_hash BYTEA NOT NULL CHECK(octet_length(semantic_hash)=32),
    reports JSONB NOT NULL CHECK(jsonb_typeof(reports)='array' AND jsonb_array_length(reports)<=100),
    PRIMARY KEY(environment,operation_id,chunk_index),
    FOREIGN KEY(environment,operation_id) REFERENCES moderation_structure_operation(environment,operation_id)
);
CREATE TABLE moderation_structure_received_event (
    environment TEXT NOT NULL,
    event_id UUID NOT NULL,
    operation_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    semantic_hash BYTEA NOT NULL CHECK(octet_length(semantic_hash)=32),
    PRIMARY KEY(environment,event_id),
    FOREIGN KEY(environment,operation_id,chunk_index) REFERENCES moderation_structure_chunk(environment,operation_id,chunk_index)
);
CREATE TABLE moderation_case_navigation (
    environment TEXT NOT NULL,
    case_id UUID NOT NULL,
    merged_into_case_id UUID NOT NULL,
    leader_case_id UUID NOT NULL,
    structure_sequence BIGINT NOT NULL CHECK(structure_sequence>0),
    operation_id UUID NOT NULL,
    PRIMARY KEY(environment,case_id),
    FOREIGN KEY(environment,operation_id) REFERENCES moderation_structure_operation(environment,operation_id),
    CHECK(case_id<>merged_into_case_id AND case_id<>leader_case_id)
);
CREATE INDEX moderation_case_navigation_leader ON moderation_case_navigation(environment,leader_case_id);
CREATE TABLE moderation_report_navigation (
    environment TEXT NOT NULL CHECK(environment IN ('stage','prod')),
    report_id UUID NOT NULL,
    case_id UUID NOT NULL,
    structure_sequence BIGINT NOT NULL CHECK(structure_sequence>=0),
    report_type TEXT NOT NULL CHECK(report_type IN ('PLAYER_REPORT','SYSTEM_MESSAGE_REPORT')),
    capture_id UUID,
    capture_status TEXT CHECK(capture_status IN ('OPEN','COMPLETE','INCOMPLETE')),
    PRIMARY KEY(environment,report_id),
    CHECK((capture_id IS NULL)=(capture_status IS NULL))
);
CREATE INDEX moderation_report_navigation_case ON moderation_report_navigation(environment,case_id);
CREATE TABLE moderation_ready_navigation_event (
    environment TEXT NOT NULL CHECK(environment IN ('stage','prod')),
    event_id UUID NOT NULL,
    report_id UUID NOT NULL,
    capture_id UUID NOT NULL,
    capture_status TEXT NOT NULL CHECK(capture_status IN ('COMPLETE','INCOMPLETE')),
    PRIMARY KEY(environment,event_id)
);
CREATE INDEX moderation_ready_navigation_report ON moderation_ready_navigation_event(environment,report_id);

CREATE FUNCTION moderation_notification_leader(p_environment TEXT,p_case_id UUID) RETURNS UUID
    LANGUAGE SQL STABLE SET search_path FROM CURRENT AS $$
    SELECT COALESCE((SELECT leader_case_id FROM moderation_case_navigation
        WHERE environment=p_environment AND case_id=p_case_id),p_case_id)
$$;
CREATE FUNCTION moderation_notification_action_case(p_environment TEXT,p_notification_id UUID,p_original UUID) RETURNS UUID
    LANGUAGE SQL STABLE SET search_path FROM CURRENT AS $$
    SELECT moderation_notification_leader(p_environment,COALESCE((
        SELECT report.case_id FROM moderation_notification_projection projection
        LEFT JOIN moderation_ready_navigation_event ready
            ON ready.environment=projection.environment AND ready.event_id=projection.source_event_id
        JOIN moderation_report_navigation report ON report.environment=projection.environment
            AND report.report_id=COALESCE(projection.navigation_report_id,ready.report_id)
        WHERE projection.environment=p_environment AND projection.notification_id=p_notification_id
    ),p_original))
$$;
CREATE FUNCTION moderation_structure_history_immutable() RETURNS trigger
    LANGUAGE plpgsql SET search_path FROM CURRENT AS $$
BEGIN
    RAISE EXCEPTION 'Moderation structure history is immutable' USING ERRCODE='55000';
END
$$;
CREATE TRIGGER moderation_structure_chunk_immutable BEFORE UPDATE OR DELETE ON moderation_structure_chunk
    FOR EACH ROW EXECUTE FUNCTION moderation_structure_history_immutable();
CREATE TRIGGER moderation_structure_received_immutable BEFORE UPDATE OR DELETE ON moderation_structure_received_event
    FOR EACH ROW EXECUTE FUNCTION moderation_structure_history_immutable();
