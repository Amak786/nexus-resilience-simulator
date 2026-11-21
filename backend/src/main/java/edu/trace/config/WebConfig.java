package edu.trace.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS (origins from configuration, never "*"), request-size filter and OpenAPI metadata. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TraceProperties props;

    public WebConfig(TraceProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = props.corsAllowedOrigins() == null ? new String[0]
                : props.corsAllowedOrigins().stream().map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
        registry.addMapping("/api/**")
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Content-Type")
                .maxAge(3600);
    }

    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter() {
        FilterRegistrationBean<RequestSizeLimitFilter> bean =
                new FilterRegistrationBean<>(new RequestSizeLimitFilter(props.maxRequestBytes()));
        bean.addUrlPatterns("/api/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    @Bean
    public OpenAPI traceOpenApi() {
        return new OpenAPI().info(new Info()
                .title("TRACE API - Network Trust, Reputation and Connectivity Explorer")
                .version("1.0.0")
                .description("Real OpenStreetMap road network of Greater Mumbai with hand-written Java algorithms: Dijkstra with a Fibonacci heap, "
                        + "Edmonds-Karp / Ford-Fulkerson with min cut, DSU, red-black tree, Bloom filter, segment tree, randomized Quickselect and "
                        + "metric-TSP 2-approximation. No live traffic; every response labels its data class.")
                .license(new License().name("MIT")));
    }
}
