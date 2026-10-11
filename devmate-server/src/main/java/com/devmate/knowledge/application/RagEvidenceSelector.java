package com.devmate.knowledge.application;

import com.devmate.knowledge.vo.RetrievalHit;
import java.util.*;
import java.util.regex.Pattern;

/** A bounded literal-coverage hint, applied only to already authorized RAG candidates. */
final class RagEvidenceSelector {
    private static final Pattern IDENTIFIER=Pattern.compile("(?<![A-Za-z0-9_])[A-Z][A-Z0-9_]{2,63}(?![A-Za-z0-9_])");
    private static final Comparator<RetrievalHit> ORDER=Comparator.comparingDouble(RetrievalHit::score).reversed().thenComparing(RetrievalHit::pointId);
    private RagEvidenceSelector() {}

    private static Set<String> identifiers(String text){
        var result=new LinkedHashSet<String>();var matcher=IDENTIFIER.matcher(text);
        while(matcher.find())result.add(matcher.group());
        return result;
    }
    static List<RetrievalHit> select(String query,List<RetrievalHit> authorized){
        var pool=authorized.stream().sorted(ORDER).limit(20).toList();
        var selected=new ArrayList<>(pool.stream().limit(5).toList());
        var terms=identifiers(query);
        if(terms.isEmpty() || terms.size()>8)return List.copyOf(selected);
        var matches=new HashMap<RetrievalHit,Set<String>>();
        for(var hit:pool){var present=identifiers(hit.text());present.retainAll(terms);matches.put(hit,present);}
        int replacements=0;
        for(var term:terms){
            var covered=new HashSet<String>();selected.forEach(h->covered.addAll(matches.get(h)));
            if(covered.contains(term))continue;
            var candidate=pool.stream().filter(h->!selected.contains(h) && matches.get(h).contains(term)).findFirst();
            if(candidate.isEmpty())continue;
            var added=candidate.get();
            // Keep every previously covered identifier, including ones introduced by an earlier swap.
            for(int index=selected.size()-1;index>=0;index--){
                var remaining=new HashSet<>(matches.get(added));
                for(int other=0;other<selected.size();other++)if(other!=index)remaining.addAll(matches.get(selected.get(other)));
                if(!remaining.containsAll(covered))continue;
                selected.set(index,added);selected.sort(ORDER);replacements++;break;
            }
            if(replacements==2)break;
        }
        return selected.stream().sorted(ORDER).toList();
    }
}
