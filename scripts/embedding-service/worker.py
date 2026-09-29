"""Single known CPU worker. No downloaded Python code, tools or configurable model URL."""
import os
import threading


def serve(connection, model_dir):
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    os.environ["TOKENIZERS_PARALLELISM"] = "false"
    os.environ["OMP_NUM_THREADS"] = "4"
    os.environ["OPENBLAS_NUM_THREADS"] = "1"
    os.environ["MKL_NUM_THREADS"] = "4"
    from spec import verify_model, tokenizer, counts
    tokenizer()  # Frozen runtime/fixture checks precede loading weights.
    verify_model(model_dir)
    import torch
    from transformers import AutoModel, AutoTokenizer
    import torch.nn.functional as functional
    torch.set_num_threads(4)
    torch.set_num_interop_threads(1)
    model = AutoModel.from_pretrained(model_dir, local_files_only=True, trust_remote_code=False,
                                      dtype=torch.float32, attn_implementation="sdpa").to("cpu").eval()
    tokens = AutoTokenizer.from_pretrained(model_dir, padding_side="left", local_files_only=True, trust_remote_code=False)
    # Readiness proves the explicit CPU mask agrees with the frozen native short-input path.
    short = tokens(["synthetic Java document", "Spring transaction rollback"], padding=True, truncation=False, return_tensors="pt")
    original_mask = short["attention_mask"].clone()
    length = short["input_ids"].shape[1]
    causal = torch.ones(length, length, dtype=torch.bool).tril()
    with torch.inference_mode():
        native = functional.normalize(model(**short, use_cache=False).last_hidden_state[:, -1, :], p=2, dim=1)
        short["attention_mask"] = causal[None, None, :, :] & original_mask[:, None, None, :].bool()
        explicit = functional.normalize(model(**short, use_cache=False).last_hidden_state[:, -1, :], p=2, dim=1)
    if not torch.allclose(native, explicit, atol=1e-5, rtol=1e-5):
        raise RuntimeError("causal/padding mask equivalence failed")

    def monitor_parent():
        import multiprocessing
        parent = multiprocessing.parent_process()
        while parent is not None and parent.is_alive():
            threading.Event().wait(1)
        os._exit(3)

    threading.Thread(target=monitor_parent, daemon=True).start()
    connection.send("READY")
    while True:
        try:
            texts, expected = connection.recv()
        except EOFError:
            return
        try:
            if [len(tokens.encode(text, add_special_tokens=True)) for text in texts] != expected:
                raise ValueError("count mismatch")
            batch = tokens(texts, padding=True, truncation=False, return_tensors="pt")
            length = batch["input_ids"].shape[1]
            causal = torch.ones(length, length, dtype=torch.bool).tril()
            original = batch["attention_mask"].clone()
            batch["attention_mask"] = causal[None, None, :, :] & original[:, None, None, :].bool()
            with torch.inference_mode():
                hidden = model(**batch, use_cache=False).last_hidden_state
                vectors = functional.normalize(hidden[:, -1, :], p=2, dim=1)
            if vectors.shape != (len(texts), 1024) or not torch.isfinite(vectors).all() or not torch.allclose(vectors.norm(dim=1), torch.ones(len(texts)), atol=1e-5):
                raise ValueError("invalid model result")
            connection.send((True, vectors.tolist()))
        except Exception:
            connection.send((False, None))  # No upstream error, source or vector logging.
