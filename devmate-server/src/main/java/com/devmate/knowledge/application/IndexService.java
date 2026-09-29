package com.devmate.knowledge.application;

import com.devmate.common.api.ErrorCode;
import com.devmate.common.exception.BusinessException;
import com.devmate.knowledge.vo.IndexResponse;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class IndexService {
    private final IndexTransactions transactions;
    public IndexService(IndexTransactions transactions){this.transactions=transactions;}
    private <T>T database(Supplier<T> action){try{return action.get();}catch(org.springframework.dao.DataAccessException|org.springframework.transaction.TransactionException failure){throw new BusinessException(ErrorCode.KNOWLEDGE_DATABASE_UNAVAILABLE);}}
    public IndexTransactions.Start start(long owner,long project,long document,String uuid){
        if(uuid==null || !uuid.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        return database(()->transactions.start(owner,project,document,UUID.fromString(uuid).toString()));
    }
    public IndexResponse status(long owner,long project,long document){return database(()->transactions.status(owner,project,document));}
}
