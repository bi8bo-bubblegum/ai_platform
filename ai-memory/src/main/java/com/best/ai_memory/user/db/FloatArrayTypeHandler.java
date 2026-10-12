package com.best.ai_memory.user.db;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(float[].class)
public class FloatArrayTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] parameter, JdbcType jdbcType) throws SQLException {
        ps.setBytes(i, VectorCodec.encode(parameter));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return VectorCodec.decode(rs.getBytes(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return VectorCodec.decode(rs.getBytes(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return VectorCodec.decode(cs.getBytes(columnIndex));
    }
}
