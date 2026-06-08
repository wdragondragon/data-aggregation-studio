package com.jdragon.studio.infra.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataDevelopmentSqlExecutorRegressionTest {

    @Test
    void sourcePluginSqlClassifierShouldTreatQueryKeywordsAsQuery() {
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("select * from t")).isTrue();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql(" show tables")).isTrue();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("desc t")).isTrue();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("describe t")).isTrue();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("with c as (select 1) select * from c")).isTrue();
    }

    @Test
    void sourcePluginSqlClassifierShouldIgnoreLeadingComments() {
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("-- comment\nselect * from t")).isTrue();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("/* comment */\nshow tables")).isTrue();
    }

    @Test
    void sourcePluginSqlClassifierShouldTreatDdlAndDmlAsUpdate() {
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("create table t(id bigint)")).isFalse();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("insert into t values(1)")).isFalse();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("alter table t add columns(c string)")).isFalse();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("drop table t")).isFalse();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("truncate table t")).isFalse();
        assertThat(DataDevelopmentSqlExecutor.isSourcePluginQuerySql("set odps.sql.allow.fullscan=true")).isFalse();
    }
}
