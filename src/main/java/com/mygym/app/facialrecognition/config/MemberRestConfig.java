package com.mygym.app.facialrecognition.config;

import static com.mygym.app.facialrecognition.util.FacialConstants.CONTENT_HEADER;
import static com.mygym.app.facialrecognition.util.FacialConstants.DEV_MEMBER_CRUD_BASE_URL;
import static com.mygym.app.facialrecognition.util.FacialConstants.JSON_HEADER;
import static com.mygym.app.facialrecognition.util.FacialConstants.MEMBER_REST_CLIENT_BEAN;
import static com.mygym.app.facialrecognition.util.FacialConstants.PRD_MEMBER_CRUD_BASE_URL;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

@Configuration
public class MemberRestConfig {

	private static final Logger LOGGER = LoggerFactory.getLogger(MemberRestConfig.class);
	
    private final ClientHttpRequestInterceptor loadBalancerInterceptor;

    @Value("${app.environment}")
    private String env;
    
    public MemberRestConfig(@Qualifier("loadBalancerInterceptor") ObjectProvider<ClientHttpRequestInterceptor> loadBalancerInterceptorProvider) {
        this.loadBalancerInterceptor = loadBalancerInterceptorProvider.getIfAvailable();
    }
	
    @Bean(name = MEMBER_REST_CLIENT_BEAN)
    public RestClient restCrudClient() {
    	String baseUrl = env.equalsIgnoreCase("DEV") ? DEV_MEMBER_CRUD_BASE_URL : PRD_MEMBER_CRUD_BASE_URL;
    	LOGGER.info("Url set to : " + baseUrl);
    	RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(CONTENT_HEADER, JSON_HEADER);
        if (loadBalancerInterceptor != null) builder.requestInterceptor(loadBalancerInterceptor);
        return builder.build();
    }
}
