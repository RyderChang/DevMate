"""Seed synthetic history/eligibility metadata only in the labelled local acceptance DB.

No upload, parser, vector store, embedding or RAG generation is executed. The
available source is a database fixture, not evidence of a stored/indexed file.
Run after creating a disposable dev035_* browser account and its first project.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import uuid

from conversation_env import owned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("username")
    arguments = parser.parse_args()
    if not re.fullmatch(r"dev035_[a-z0-9]+", arguments.username):
        parser.error("Expected a disposable dev035_* account")
    if not all(owned(f"devmate015-{kind}") for kind in ("db", "app", "stub")):
        raise RuntimeError("Start the labelled local acceptance environment first")

    spec = importlib.util.spec_from_file_location(
        "acceptance_api", Path(__file__).with_name("check-conversation-api.py"))
    api = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(api)

    def value(text):
        return "CONVERT(0x" + text.encode("utf-8").hex() + " USING utf8mb4)"

    identity = api.sql("SELECT p.owner_user_id,p.id FROM projects p JOIN users u ON u.id=p.owner_user_id "
                       f"WHERE u.username='{arguments.username}' AND p.deleted=0 ORDER BY p.id LIMIT 1")
    if not re.fullmatch(r"[0-9]+\s+[0-9]+", identity):
        raise RuntimeError("Create the disposable browser account and project first")
    owner, project = identity.split()
    title = "Synthetic citation history (metadata fixture)"
    if api.sql(f"SELECT COUNT(*) FROM conversations WHERE project_id={project} AND title={value(title)}") != "0":
        raise RuntimeError("Fixture already exists; use a fresh disposable project")

    text = "Synthetic citation source. No model or storage request."
    sha = hashlib.sha256(text.encode()).hexdigest()
    filename = "Synthetic_" + "LongSourceName_" * 7 + "<img src=x onerror=alert(1)>.md"
    point = str(uuid.uuid4())
    source = dict(pointId=point, filename=filename, processingGeneration=1, indexGeneration=1,
                  parserVersion="acceptance-parser", strategyVersion="acceptance-chunker",
                  sourceSha256=sha, chunkSha256=sha, ordinal=0, start=0, end=len(text), startLine=1, endLine=1)

    # One transaction leaves no half-created fixture on a constraint failure.
    sql = f"""
START TRANSACTION;
INSERT INTO knowledge_documents(owner_user_id,project_id,filename,file_type,byte_size,sha256,bucket,object_key,
 put_token,storage_state,remote_phase,next_attempt_at,create_time,update_time)
VALUES({owner},{project},{value(filename)},'md',{len(text)},'{sha}','synthetic-no-storage',
 'acceptance/{uuid.uuid4()}','{uuid.uuid4()}','STORED','FINISHED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
SET @document=LAST_INSERT_ID();
INSERT INTO knowledge_processing(owner_user_id,project_id,document_id,generation,source_sha256,normalized_sha256,
 parser_version,strategy_version,state,active,chunk_count,text_bytes,used_bytes,reserved_bytes,
 next_attempt_at,create_time,update_time)
VALUES({owner},{project},@document,1,'{sha}','{sha}','acceptance-parser','acceptance-chunker','CHUNKED',1,1,
 {len(text)},{len(text)},0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
SET @processing=LAST_INSERT_ID();
INSERT INTO knowledge_chunks(owner_user_id,project_id,document_id,processing_id,ordinal,start_offset,end_offset,
 start_line,end_line,text,sha256,byte_size)
VALUES({owner},{project},@document,@processing,0,0,{len(text)},1,1,{value(text)},'{sha}',{len(text)});
INSERT INTO knowledge_indexes(owner_user_id,project_id,document_id,processing_id,generation,source_sha256,
 manifest_sha256,spec,state,active,chunk_count,reserved_bytes,reserved_debts,next_attempt_at,create_time)
VALUES({owner},{project},@document,@processing,1,'{sha}','{sha}','qwen3-0.6b-1024-cosine-v1',
 'SUCCEEDED',1,1,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
SET @index=LAST_INSERT_ID();
INSERT INTO knowledge_index_points(index_id,ordinal,point_id,chunk_sha256,confirmed)
VALUES(@index,0,'{point}','{sha}',1);
UPDATE knowledge_documents SET active_processing_id=@processing,active_index_id=@index,
 next_processing_generation=2,next_index_generation=2 WHERE id=@document;
INSERT INTO conversations(project_id,owner_user_id,title) VALUES({project},{owner},{value(title)});
SET @conversation=LAST_INSERT_ID();
INSERT INTO conversation_messages(conversation_id,sequence_no,role,content)
VALUES(@conversation,1,'USER','Synthetic history question: show saved citations.');
SET @user_message=LAST_INSERT_ID();
INSERT INTO conversation_messages(conversation_id,sequence_no,role,content)
VALUES(@conversation,2,'ASSISTANT','Synthetic saved answer [C1] [C2]. Citation locations do not verify answer facts.');
SET @assistant_message=LAST_INSERT_ID();
INSERT INTO ai_invocations(conversation_id,client_request_id,user_message_id,assistant_message_id,provider,model,
 prompt_template_version,status,started_at,completed_at,mode,request_sha256,lease_expires_at)
VALUES(@conversation,'{uuid.uuid4()}',@user_message,@assistant_message,'openai','acceptance-model',
 'project-rag-v1','SUCCEEDED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),'RAG','{sha}',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR));
SET @invocation=LAST_INSERT_ID();
INSERT INTO rag_invocation_details(invocation_id,execution_deadline,retrieval_id,spec,query_tokens,rounds,
 inspected_points,checked_at,chat_state)
VALUES(@invocation,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),'{uuid.uuid4()}',
 'qwen3-0.6b-1024-cosine-v1',5,1,2,UTC_TIMESTAMP(6),'RECEIVED');
"""
    snapshot = value(json.dumps(source))
    sql += f"""
SET @snapshot=JSON_SET({snapshot},'$.documentId',@document,'$.processingId',@processing,'$.indexId',@index);
INSERT INTO rag_citations(invocation_id,citation_id,source_snapshot) VALUES(@invocation,'C1',@snapshot);
INSERT INTO rag_citations(invocation_id,citation_id,source_snapshot)
VALUES(@invocation,'C2',JSON_SET(@snapshot,'$.pointId','{uuid.uuid4()}','$.filename','Unavailable synthetic source.md'));
COMMIT;
SELECT @conversation;
"""
    conversation = api.sql(sql)
    if not conversation.isdigit():
        raise RuntimeError("Fixture did not return a conversation ID")
    print(f"Seeded synthetic metadata only: /projects/{project}/conversations/{conversation}")


if __name__ == "__main__":
    main()
