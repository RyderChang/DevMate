"""Seed synthetic pagination data for an existing browser-created acceptance project."""
import argparse
import importlib.util
from pathlib import Path
import re
from conversation_env import owned

# Reuse the fixed, task-scoped metadata SQL transport; never export account credentials.
spec = importlib.util.spec_from_file_location("acceptance_api", Path(__file__).with_name("check-conversation-api.py"))
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("username")
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9_-]{3,50}", args.username):
    parser.error("Expected a synthetic username")
assert owned("devmate015-db")
project = api.sql("SELECT p.id FROM projects p JOIN users u ON u.id=p.owner_user_id "
                  f"WHERE u.username='{args.username}' AND p.deleted=0 ORDER BY p.id LIMIT 1")
if not project.isdigit():
    raise RuntimeError("Create the browser account and project first")
cid = api.sql("INSERT INTO conversations(project_id,owner_user_id,title) "
              f"SELECT id,owner_user_id,'Synthetic 52-message history' FROM projects WHERE id={project}; SELECT LAST_INSERT_ID()")
if not cid.isdigit():
    raise RuntimeError("Failed to create history fixture")
rows = ",".join(f"({cid},{n},'{('USER' if n % 2 else 'ASSISTANT')}','Synthetic history {n:02d}')" for n in range(1, 53))
api.sql("INSERT INTO conversation_messages(conversation_id,sequence_no,role,content) VALUES " + rows)
for n in range(12):
    api.sql("INSERT INTO conversations(project_id,owner_user_id,title) "
            f"SELECT id,owner_user_id,'Synthetic list item {n + 1:02d}' FROM projects WHERE id={project}")
print(f"Seeded synthetic UI pagination fixture: /projects/{project}/conversations/{cid}")
