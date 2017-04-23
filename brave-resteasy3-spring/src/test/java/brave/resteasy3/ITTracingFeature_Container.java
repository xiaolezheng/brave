package brave.resteasy3;

import brave.Tracer;
import brave.Tracing;
import brave.http.ITServletContainer;
import brave.jaxrs2.TracingContainerFilter;
import brave.parser.Parser;
import java.io.IOException;
import java.util.function.Supplier;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.plugins.server.servlet.ResteasyBootstrap;
import org.jboss.resteasy.plugins.spring.SpringContextLoaderListener;
import org.junit.AssumptionViolatedException;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

public class ITTracingFeature_Container extends ITServletContainer {

  @Override @Test public void reportsClientAddress() {
    throw new AssumptionViolatedException("ContainerRequestContext doesn't include remote address");
  }

  @Path("")
  public static class TestResource {
    final Tracer tracer;

    @Autowired
    public TestResource(Tracing tracing) {
      this.tracer = tracing.tracer();
    }

    @GET
    @Path("foo")
    public Response get() {
      return Response.status(200).build();
    }

    @GET
    @Path("child")
    public Response child() {
      tracer.nextSpan().name("child").start().finish();
      return Response.status(200).build();
    }

    @GET
    @Path("async")
    public void async(@Suspended AsyncResponse response) throws IOException {
      new Thread(() -> response.resume("ok")).start();
    }

    @GET
    @Path("disconnect")
    public Response disconnect() throws IOException {
      throw new IOException();
    }

    @GET
    @Path("disconnectAsync")
    public void disconnectAsync(@Suspended AsyncResponse response) throws IOException {
      new Thread(() -> response.resume(new IOException())).start();
    }
  }

  /**
   * {@link ContainerResponseFilter} has no means to handle uncaught exceptions. Unless you provide
   * a catch-all exception mapper, requests that result in unhandled exceptions will leak until they
   * are eventually flushed.
   */
  @Provider
  public static class CatchAllExceptions implements ExceptionMapper<Exception> {

    @Override
    public Response toResponse(Exception e) {
      if (e instanceof WebApplicationException) {
        return ((WebApplicationException) e).getResponse();
      }

      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity("Internal error")
          .type("text/plain")
          .build();
    }
  }

  @Override
  public void init(ServletContextHandler handler, Tracing tracing, Supplier<String> spanName) {
    TracingContainerFilter.Config containerConfig = new TracingContainerFilter.Config() {
      @Override protected Parser<ContainerRequestContext, String> spanNameParser() {
        return spanName != null ? req -> spanName.get() : super.spanNameParser();
      }
    };
    AnnotationConfigWebApplicationContext appContext =
        new AnnotationConfigWebApplicationContext() {
          // overriding this allows us to register dependencies of BraveTracingHandlerInterceptor
          // without passing static state to a configuration class.
          @Override protected void loadBeanDefinitions(DefaultListableBeanFactory beanFactory) {
            beanFactory.registerSingleton("tracing", tracing);
            beanFactory.registerSingleton("containerConfig", containerConfig);
            super.loadBeanDefinitions(beanFactory);
          }
        };

    appContext.register(TestResource.class); // the test resource
    appContext.register(CatchAllExceptions.class);
    appContext.register(TracingFeatureConfiguration.class); // generic tracing setup

    // resteasy + spring configuration, programmatically as opposed to using web.xml
    handler.addServlet(new ServletHolder(new HttpServletDispatcher()), "/*");
    handler.addEventListener(new ResteasyBootstrap());
    handler.addEventListener(new SpringContextLoaderListener(appContext));
  }
}
