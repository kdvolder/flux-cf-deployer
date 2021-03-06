/*******************************************************************************
 * Copyright (c) 2014 Pivotal Software, Inc. and others.
 * All rights reserved. This program and the accompanying materials are made 
 * available under the terms of the Eclipse Public License v1.0 
 * (http://www.eclipse.org/legal/epl-v10.html), and the Eclipse Distribution 
 * License v1.0 (http://www.eclipse.org/org/documents/edl-v10.html). 
 *
 * Contributors:
 *     Pivotal Software, Inc. - initial API and implementation
*******************************************************************************/
package org.springframework.social.showcase.cloudfoundry;

import java.net.URLEncoder;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.springframework.core.env.Environment;
import org.springframework.social.showcase.flux.support.Flux;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestTemplate;

@Controller
public class CloudfoundryController {

	private CloudFoundryManager cfm;
	
	@Inject
	Flux flux;
	
	@Inject
	private RestTemplate rest;
	
	@Inject
	public CloudfoundryController(CloudFoundryManager cfm) {
		this.cfm = cfm;
	}
	
	@Inject
	private Environment env;

	@RequestMapping("/cloudfoundry/deploy")
	public String deploy(Principal currentUser, Model model) throws Exception {
		try {
			System.out.println("Handling /cloudfoundry/deploy");
			CloudFoundry cf = cfm.getConnection(currentUser);
			if (!isLoggedIn(cf)) {
				return "redirect:/cloudfoundry/login";
			}
			System.out.println("Cloudfoundry isLoggedIn => OK");
			if (flux==null) {
				return "redirect:/singin/flux";
			}
			System.out.println("flux = "+flux);
			
			String defaultSpace = cf.getSpace();
			System.out.println("defaultSpace = "+defaultSpace);
			
			//The page should show a list of flux project with their deployment status.
			List<String> projects = flux.getProjects();
			System.out.println("projects = "+projects);
			String[] spaces = cf.getSpaces(flux.getMessagingConnector());
			System.out.println("spaces = "+spaces);
			
			model.addAttribute("user", cf.getUser());
			model.addAttribute("projects", projects);
			model.addAttribute("spaces", spaces);
			model.addAttribute("defaultSpace", defaultSpace);
			
			if (projects.isEmpty()) {
				model.addAttribute("error_message", "Nothing to deploy: You don't have any Flux projects!");
			}
			
			List<DeploymentConfig> deployments = new ArrayList<DeploymentConfig>();
			for (String pname : projects) {
				DeploymentConfig deployConf = cf.getDeploymentConfig(flux.getMessagingConnector(), pname);
				deployments.add(deployConf);
			}
			model.addAttribute("deployments", deployments);
			return "cloudfoundry/deploy";
		} catch (Throwable e) {
			e.printStackTrace();
			//trouble happens is cf service gets restarted and is no longer in logged in state, but
			// deployer app still thinks that it is. This can be solved by logging in again.
			return "redirect:/cloudfoundry/login?error="+URLEncoder.encode(CloudFoundryErrors.errorMessage(e), "UTF8");
		}
	}

	private boolean isLoggedIn(CloudFoundry cf) {
		return cf!=null && cf.isLoggedIn();
	}

	@RequestMapping(value="/cloudfoundry/deploy.do", method=RequestMethod.POST)
	public String deployDo(Principal currentUser, 
			@RequestParam("project") String project,
			@RequestParam("space") String space,
			Model model
		) throws Exception {
		try {
			CloudFoundry cf = cfm.getConnection(currentUser);
			if (!isLoggedIn(cf)) {
				return "redirect:/cloudfoundry/login";
			}
			if (flux==null) {
				return "redirect:/singin/flux";
			}
			DeploymentConfig dep = new DeploymentConfig(project);
			dep.setCfOrgSpace(space);
			cf.setSpace(space); //use this as default space from now on
			cf.push(flux.getMessagingConnector(), dep);
			return "redirect:/cloudfoundry/app-log"
				+"?space="+URLEncoder.encode(space, "UTF-8")
				+"&project="+URLEncoder.encode(project, "UTF-8");
		} catch (Throwable e) {
			e.printStackTrace();
			//Broken cfdeployer service state, or service died. If the service is still there
			// it likely has not been properly logged in for this user so redirect user to login page
			// and show error message there.
			return "redirect:/cloudfoundry/login?error="+URLEncoder.encode(CloudFoundryErrors.errorMessage(e), "UTF8");
		}
	}
	
	@RequestMapping(value="/cloudfoundry")
	public String profile(Principal currentUser, Model model) throws Exception {
		CloudFoundry cf = cfm.getConnection(currentUser);
		if (!isLoggedIn(cf)) {
			return "redirect:/cloudfoundry/login";
		}
		model.addAttribute("space", cf.getSpace());
		model.addAttribute("user", cf.getUser());
		model.addAttribute("spaces", cf.getSpaces(flux.getMessagingConnector()));
		return "cloudfoundry";
	}
	
	@RequestMapping(value="/cloudfoundry/processLogin")
	public String doLogin(Principal currentUser, Model model,
		@RequestParam(required=false, value="cf_login") String login, 
		@RequestParam(required=false, value="cf_password") String password
	) throws Exception {
		//Info about oauth on CF: 
		// https://github.com/cloudfoundry/uaa/blob/master/docs/UAA-APIs.rst#implicit-grant-with-credentials-post-oauthauthorize
		
		CloudFoundry cf = new CloudFoundry(
				env.getProperty("cloudfoundry.url", "https://api.run.pivotal.io/"),
				rest);
		
		try {
			cf.login(flux, login, password, null);
			cfm.putConnection(currentUser, cf);
			return "redirect:/cloudfoundry/deploy";
		} catch (Throwable e) {
			e.printStackTrace();
			return "redirect:/cloudfoundry/login?error="+URLEncoder.encode(CloudFoundryErrors.errorMessage(e), "UTF8");
		}
	}
	
	@RequestMapping("/cloudfoundry/login")
	public String login() {
		return "cloudfoundry/login";
	}
	
	@RequestMapping("/cloudfoundry/app-log")
	public String appLogs(Principal currentUser, Model model,
		@RequestParam("space") String orgSpace,
		@RequestParam("project") String project
	) throws Exception {
		if (flux==null) {
			return "redirect:/singin/flux";
		}
		CloudFoundry cf = cfm.getConnection(currentUser);
		if (cf==null) {
			return "redirect:/cloudfoundry/login";
		}
		String [] pieces = orgSpace.split("/");
		model.addAttribute("org",pieces[0]);
		model.addAttribute("space", pieces[1]);
		model.addAttribute("app", project);
		model.addAttribute("routes", cf.getDeploymentConfig(flux.getMessagingConnector(), project).getRoutes());
		
		model.addAttribute("fluxUser", flux.getUserProfile().getLogin());
		model.addAttribute("fluxHost", flux.getMessagingConnector().getConfig().toSocketIO().getHost());
		model.addAttribute("fluxToken", flux.getAccessToken());
		return "cloudfoundry/app-log";
	}

}
