package com.devmate.knowledge.application;

import com.devmate.ai.embedding.EmbeddingSpec;
import com.devmate.knowledge.infrastructure.RetrievalJournal;
import com.devmate.knowledge.retrieval.VectorCandidate;
import com.devmate.knowledge.vo.RetrievalHit;
import com.devmate.project.service.ProjectService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Public knowledge boundary. Publication joins the caller's project lock and transaction. */
@Service
public class RagSourceEligibility {
    private final RetrievalTransactions retrieval;
    private final RetrievalJournal journal;
    private final ProjectService projects;

    public RagSourceEligibility(RetrievalTransactions retrieval, RetrievalJournal journal, ProjectService projects) {
        this.retrieval = retrieval; this.journal = journal; this.projects = projects;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean validate(long owner, long project, List<RetrievalHit> hits) {
        if (hits.isEmpty() || hits.size() > 5) return false;
        projects.lockOwnedActiveProject(owner, project);
        return same(hits, retrieval.hydrate(owner, project, candidates(hits)));
    }

    /** Historical availability needs no source body and remains readable with new writes disabled. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> available(long owner, long project, List<RetrievalHit> snapshots) {
        projects.lockOwnedActiveProject(owner, project);
        return journal.available(owner, project, EmbeddingSpec.ID, snapshots);
    }

    private List<VectorCandidate> candidates(List<RetrievalHit> hits) {
        return hits.stream().map(h -> new VectorCandidate(h.pointId(), h.score(),
                h.documentId()+"/"+h.processingId()+"/"+h.indexId()+"/"+EmbeddingSpec.ID,
                h.ordinal(), h.chunkSha256(), h.sourceSha256())).toList();
    }

    private boolean same(List<RetrievalHit> expected, List<RetrievalHit> actual) {
        return expected.size() == actual.size() && expected.stream().allMatch(actual::contains);
    }
}
