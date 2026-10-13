package com.backend.meety.global.migration;

import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;


@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "migration.write-gate.enabled", havingValue = "true")
public class MigrationGateConfiguration {
    @Bean
    public static BeanFactoryPostProcessor migrationGateSchemaGuard(Environment environment) {

        return beanFactory -> {
            String ddl = environment.getProperty("spring.jpa.hibernate.ddl-auto", "");
            if (!"none".equalsIgnoreCase(ddl) && !"validate".equalsIgnoreCase(ddl)) {
                throw new IllegalStateException("쓰기 게이트 활성화 시 spring.jpa.hibernate.ddl-auto를 none 또는 validate로 지정해야 합니다.");
            }
        };
    }

    @Bean

    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public static Advisor migrationGateAdvisor(ObjectProvider<MigrationWriteGate> gates) {


        var transactions = new AnnotationTransactionAttributeSource(false);
        var pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {

                if (!targetClass.getName().startsWith("com.backend.meety.domain.")) {
                    return false;
                }

                Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
                var attribute = transactions.getTransactionAttribute(method, targetClass);

                return isScheduled(specific) || isMutatingHandler(specific)
                        || (attribute != null && !attribute.isReadOnly());
            }
        };
        MethodInterceptor interceptor = invocation -> {

            var gate = gates.getObject();
            Method method = AopUtils.getMostSpecificMethod(invocation.getMethod(),
                    AopUtils.getTargetClass(invocation.getThis()));



            if (!gate.ownsPermit() && TransactionSynchronizationManager.isActualTransactionActive()) {
                var permit = gate.tryEnter();
                if (permit == null) {
                    throw new MigrationGateUnavailableException("트랜잭션 시작 전에 쓰기 게이트를 통과해야 합니다.");
                }
                try (permit) {
                    return invocation.proceed();
                }
            }

            try (var permit = isScheduled(method) ? gate.tryEnter() : gate.enter()) {

                if (permit == null) {
                    return null;
                }
                return invocation.proceed();
            }
        };

        var advisor = new DefaultPointcutAdvisor(pointcut, interceptor);

        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE + 100);
        return advisor;
    }

    private static boolean isScheduled(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, Scheduled.class);
    }

    private static boolean isMutatingHandler(Method method) {

        var mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
            return false;
        }

        if (mapping.method().length == 0) {
            return true;
        }

        for (RequestMethod verb : mapping.method()) {
            if (verb != RequestMethod.GET && verb != RequestMethod.HEAD && verb != RequestMethod.OPTIONS) {
                return true;
            }
        }
        return false;
    }
}
