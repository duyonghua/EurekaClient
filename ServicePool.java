package com.jb.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import com.netflix.appinfo.InstanceInfo;
@Service
public class ServicePool {
	
	@Autowired
	private Environment environment;
	/**
	 * 曾经存在，但是出过问题的服务
	 */
	private static List<String> badServicesPool=new ArrayList<>();
	/**
	 * 已经使用过的服务池
	 */
	private static HashMap<String,List<String>> userservicesPool=new HashMap<>();
	private static ServiceEnty getService(String paramURL) {
		ServiceEnty serviceEnty=null;
		 //创建一个httpClient实例
        CloseableHttpClient httpClient = HttpClients.createDefault();
        //创建一个get方法,并指定url
        HttpGet get = new HttpGet(paramURL);

        {
            try {
                HttpResponse  response = httpClient.execute(get);
                int httpCode = response.getStatusLine().getStatusCode();
                if(httpCode >= 200 && httpCode < 400){
                    HttpEntity httpEntity = response.getEntity();
                    String result = EntityUtils.toString(httpEntity);
                    serviceEnty=ServiceEnty.parse(result);
                }
    	        httpClient.close();
            } catch (IOException e) {
            	badServicesPool.add(paramURL);
            	System.out.println(paramURL);
                e.printStackTrace();
            }

        }
		return serviceEnty;
	}
	/**
	 * 检查坏的服务
	 */
	@Bean
	public boolean scanBadService() {
		ScheduledExecutorService  scheduledThreadPool = Executors.newScheduledThreadPool(5);
		Thread thread=new Thread(new Runnable() {
			
			@Override
			public void run() {
				for(int i=badServicesPool.size()-1;i>=0;--i) {
					System.out.println("scan"+badServicesPool.get(i));
					ServiceEnty enty=getService(badServicesPool.get(i));
					if(enty!=null&&enty.isThriftPrepare()) {
						badServicesPool.remove(badServicesPool.get(i));
					}
				}
				
			}
		});
		int unit=1;
		try {
			unit=environment.getProperty("scantime",int.class);
		}catch(Exception e){
			unit=1;
		}
		scheduledThreadPool.scheduleAtFixedRate(thread, 1,unit, TimeUnit.SECONDS);
		return true;
		  
	}
	/**
	 * 收集服务信息
	 * @param paramAppName
	 * @param paramInstances
	 */
	public static ServiceEnty getServiceInfo(String paramAppName,List<InstanceInfo> paramInstances) {
		String url="";
		ServiceEnty serviceEnty=null;
		//取出待用服务
		List<String> waitServices=userservicesPool.get(paramAppName);
		
		if(waitServices==null||waitServices.size()==0) {
			for(int i=0;i<paramInstances.size();++i) {
				url=paramInstances.get(i).getStatusPageUrl();
				if(badServicesPool.contains(url)) {
					continue;
				}
				serviceEnty=getService(url);
				if((serviceEnty!=null)&&(serviceEnty.isThriftPrepare())) {
					List<String> services=userservicesPool.get(paramAppName);
					if(services==null) {
						services=new ArrayList<>();
						userservicesPool.put(paramAppName, services);
					}
					services.add(url);
					break;
				}
			}
		}else {
			//已经下线的服务列表
			List<String> downloadservice=new ArrayList<>();
			InstanceInfo instanceInfo=null;
			for(int i=0;i<paramInstances.size();++i) {
				url=paramInstances.get(i).getStatusPageUrl();
				if(badServicesPool.contains(url)) {
					continue;
				}
				if(instanceInfo==null) {
					instanceInfo=paramInstances.get(i);
				}
				if(!waitServices.contains(url)){
					serviceEnty = getService(url);
					if((serviceEnty!=null)&&(serviceEnty.isThriftPrepare())) {//写入已用池
						List<String> services=userservicesPool.get(paramAppName);
						if(services==null) {
							services=new ArrayList<>();
							userservicesPool.put(paramAppName, services);
						}
						if(!services.contains(url)) {
							services.add(url);						
						}
					}else {
						downloadservice.add(url);
					}
					
				}
			}

			if(serviceEnty==null) {//沒有找到可用的服務，有可能是一圈輪完了取出第一個
				userservicesPool.clear();
				if(instanceInfo!=null) {
					url=instanceInfo.getStatusPageUrl();
					serviceEnty = getService(url);
				}
			}
			for(String s:downloadservice) {
				waitServices.remove(s);
			}
		}
		System.out.println(url);
		return serviceEnty;
	}
	
	

}
