package brave.resteasy3;

import brave.Tracing;
import brave.jaxrs2.TracingClientFilter;
import brave.jaxrs2.TracingContainerFilter;
import brave.jaxrs2.TracingFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Imports jaxrs2 filters used in resteasy3. */
@Configuration
public class TracingFeatureConfiguration {
  // instead of @Conditional or @ConditionalOnMissingBean in order to support spring 3.x
  @Autowired(required = false)
  TracingClientFilter.Config clientConfig = new TracingClientFilter.Config();
  @Autowired(required = false)
  TracingContainerFilter.Config containerConfig = new TracingContainerFilter.Config();

  @Bean public TracingFeature braveTracingFeature(Tracing tracing) {
    return TracingFeature.builder(tracing)
        .clientConfig(clientConfig)
        .containerConfig(containerConfig).build();
  }
}