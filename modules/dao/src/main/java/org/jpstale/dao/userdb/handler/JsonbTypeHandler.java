package org.jpstale.dao.userdb.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * `jsonb` 列 ⇄ `String`。DAO 层只搬文本，**不解析也不校验**（解析只有一份：`PlayerProperties`）。
 *
 * <p>⚠ 写必须 `setObject(i, json, Types.OTHER)`：pgjdbc 的 `setString` 默认按 **varchar** 发参数，
 * 而 Postgres 不接受 varchar → jsonb（会报 `column … is of type jsonb but expression is of type
 * character varying`）；`Types.OTHER` 在驱动里落到 `Oid.UNSPECIFIED`，由服务端按列类型解析
 * —— 这就是本项目**不必改 JDBC URL**（`stringtype=unspecified`）也能写 jsonb 的原因。
 * 改成 `setString` 会**上线即写不进去**，故有 `JsonbTypeHandlerTest` 钉住这个调用。
 *
 * <p>读走 `getString`（与默认的 `StringTypeHandler` 同一条路径）⇒ 不需要在 `@TableName` 上开
 * `autoResultMap`，也不必往全局 TypeHandlerRegistry 注册（注册会连累所有 String 列的映射）。
 */
public class JsonbTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, Types.OTHER);
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return rs.getString(columnName);
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return cs.getString(columnIndex);
    }
}
