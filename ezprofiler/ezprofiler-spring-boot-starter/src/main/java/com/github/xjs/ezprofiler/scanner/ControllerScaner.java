package com.github.xjs.ezprofiler.scanner;

import com.github.xjs.ezprofiler.annotation.Profiler;
import com.github.xjs.ezprofiler.config.EzProfilerProperties;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @author 605162215@qq.com
 * @date 2018年6月29日 下午12:59:59<br/>
 */
@Service
public class ControllerScaner implements BeanPostProcessor {

    private static Logger log = LoggerFactory.getLogger(ControllerScaner.class);

    @Autowired
    EzProfilerProperties properties;

    private ProfilerQueue queue = new ProfilerQueue();

    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    /**
     * 拦截Controller和RestController类，生成他们的子类
     */
    public Object postProcessAfterInitialization(final Object bean, final String beanName) throws BeansException {
        final Class<?> beanClass = bean.getClass();
        final String beanClassName = beanClass.getName();
        String basePackage = properties.getBasePackage();// env.getProperty("ezprofiler.basepackage", "com");
        if (!beanClassName.startsWith(basePackage)) {
            return bean;
        }
        if (beanClassName.startsWith("org.springframework") || beanClassName.indexOf("EzProfilerController") >= 0) {
            return bean;
        }
        if (!AnnotatedElementUtils.hasAnnotation(beanClass, Controller.class)) {
            return bean;
        }
        Profiler profiler = AnnotationUtils.findAnnotation(beanClass, Profiler.class);
        if (profiler != null && !profiler.enable()) {//类上没有启用profiler
            return bean;
        }
        log.info("find controller:{}", beanName);
        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTarget(bean);
        proxyFactory.addAdvice(new MethodInterceptor() {
            public Object invoke(MethodInvocation invocation) throws Throwable {
                Method method = invocation.getMethod();
                Profiler methodProfiler = AnnotationUtils.findAnnotation(method, Profiler.class);
                //方法上没有启用
                if (methodProfiler != null && !methodProfiler.enable()) {
                    return method.invoke(bean, invocation.getArguments());
                }
                //不是一个requestMapping方法
                RequestMapping requestMappingAnnotation = AnnotatedElementUtils.getMergedAnnotation(method, RequestMapping.class);
                if (requestMappingAnnotation == null) {
                    return method.invoke(bean, invocation.getArguments());
                }
                //开始统计
                String uri = "";
                long startAt = 0;
                long endAt = 0;
                boolean occurError = true;
                try {
                    uri = requestMappingAnnotation.value()[0];
                    startAt = System.currentTimeMillis();
                    Object result = method.invoke(bean, invocation.getArguments());
                    endAt = System.currentTimeMillis();
                    occurError = false;
                    return result;
                } catch (InvocationTargetException e) {
                    endAt = System.currentTimeMillis();
                    occurError = true;
                    Throwable t = e.getTargetException();// 获取目标异常
                    throw t;
                } finally {
                    ProfileInfo info = new ProfileInfo();
                    info.setStart(startAt);
                    info.setEnd(endAt);
                    info.setUri(uri);
                    info.setClazz(beanClass);
                    info.setMethod(method);
                    info.setOccurError(occurError);
                    //入队
                    queue.addProfileInfo(info);
                }
            }
        });
        return proxyFactory.getProxy();
    }

}
