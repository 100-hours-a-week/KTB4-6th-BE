package com.backend.meety.global.config;

import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    private static final String WRITE = "write";
    private static final String READ = "read";

    public ReadWriteRoutingDataSource(DataSource writeDataSource, DataSource readDataSource) {
        setTargetDataSources(Map.of(WRITE, writeDataSource, READ, readDataSource));
        setDefaultTargetDataSource(writeDataSource);
        afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly() && !ReadMasterContext.isForced()) {
            return READ;
        }
        return WRITE;
    }
}
