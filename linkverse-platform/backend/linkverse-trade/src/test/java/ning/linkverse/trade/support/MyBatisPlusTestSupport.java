package ning.linkverse.trade.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

import javax.sql.DataSource;

/**
 * MyBatisPlusTestSupport 为 Trade 非 Spring 容器测试创建真实 Mapper 代理。
 *
 * @author ning
 * @date 2026-08-31
 */
public final class MyBatisPlusTestSupport {

    private MyBatisPlusTestSupport() {
    }

    public static SqlSessionTemplate create(DataSource dataSource, Class<?>... mapperTypes) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        configuration.setEnvironment(new Environment(
                "trade-test",
                new SpringManagedTransactionFactory(),
                dataSource
        ));
        for (Class<?> mapperType : mapperTypes) {
            addMapper(configuration, mapperType);
        }
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory);
    }

    private static <T> void addMapper(MybatisConfiguration configuration, Class<T> mapperType) {
        configuration.addMapper(mapperType);
    }
}
