package i5.las2peer.restMapper;

import static org.junit.Assert.*;


import i5.las2peer.restMapper.data.InvocationData;
import i5.las2peer.restMapper.data.Pair;
import i5.las2peer.restMapper.data.PathTree;

import java.lang.reflect.Method;



import org.junit.BeforeClass;
import org.junit.Test;

public class RESTMapperTest {

	
	TestClass1 testClass1= new TestClass1();
	
	static PathTree tree;
	static PathTree tree2;
	@BeforeClass
	public static void testSetup() 
	{			
		
		String xml="";
		String xml2="";
		try {
			//tree=mapper.getMappingTree(mapper.getMethodsAsXML(TestClass1.class));
			xml=RESTMapper.getMethodsAsXML(TestClass1.class);
			xml2=RESTMapper.getMethodsAsXML(TestClass2.class);
			//System.out.println(xml);
			tree=RESTMapper.getMappingTree(xml);
			tree2=RESTMapper.getMappingTree(xml2);
			tree.merge(tree2);
            //System.out.println(RESTMapper.mergeXMLs(new String[]{xml,xml2}));

        } catch (Exception e) {
			
			e.printStackTrace();
		}
	}
	
	public void invokeMethod(String httpMethod, String uri, Pair<String>[] variables, String content, Pair<String>[] httpHeaders, String assertionMessage, String assertion) throws Exception
	{
		
			InvocationData[] invocation =RESTMapper.parse(tree, httpMethod, uri, variables, content, httpHeaders);
            if (invocation.length==0)
                fail("no method found for " +assertionMessage);
			for (int i = 0; i < invocation.length; i++) {
				Object[] parameters= invocation[i].getParameters();
				Class<?> [] parameterTypes =invocation[i].getParameterTypes();
				
				//System.out.println(invocation[i].getServiceName());
				//System.out.println(invocation[i].getMethodName());
				Class<?> clazz=Class.forName(invocation[i].getServiceName());
				Method method=clazz.getMethod(invocation[i].getMethodName(), parameterTypes);
				
				Object obj=clazz.newInstance();
				Object result=method.invoke(obj, parameters);
				String r=RESTMapper.castToString(result);
				assertEquals(assertionMessage,assertion,r);

                //System.out.println(r);
				//System.out.println("__");
			}

			
		
		
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testA1() {
		try {
			invokeMethod("get","",new Pair[]{},"",new Pair[]{},"a1","true");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			fail(e.getMessage());
		}	
		
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testA2(){
		
		try {
			invokeMethod("put","/users/205",new Pair[]{},"",new Pair[]{},"a2","20.5");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			fail(e.getMessage());
		}	
		
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testA3(){
		try {
			invokeMethod("delete","/users/12/products/5/true",new Pair[]{},"",new Pair[]{},"a3","60true");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			fail(e.getMessage());
		}	
		
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testA4(){
		try {
			invokeMethod("post","hi/b/c",new Pair[]{new Pair<String>("d","12"),new Pair<String>("e","4")},"",new Pair[]{},"a4","hi16");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			fail(e.getMessage());
		}
		
	}
	@SuppressWarnings("unchecked")
	@Test
	public void testA4_default(){
		try {

           invokeMethod("post","hi/b/c",new Pair[]{},"",new Pair[]{},"a4_default","hi24");


		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			fail(e.getMessage());
		}	
		
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testA4_ex(){
		
		try	{
			invokeMethod("post","hi/b/",new Pair[]{new Pair<String>("d","12"),new Pair<String>("e","4")},"",new Pair[]{},"a4_ex","hi9");
			
			fail("Wrong path, no exception");
		}
		catch (Exception e)	{
			//e.printStackTrace();
		}
	}
	@SuppressWarnings("unchecked")
	@Test
	public void testA5(){
		try	{
			invokeMethod("get","a",new Pair[]{},"t",new Pair[]{},"a5","tt");
		}
		catch (Throwable e)	{
			e.printStackTrace();
			fail(e.getMessage());
		}
	}
	@SuppressWarnings("unchecked")
	@Test
	public void testA6(){
		try	{
			invokeMethod("put","d/c/b/1",new Pair[]{},"",new Pair[]{},"a6","dcb1");
			
		}
		catch (Throwable e)	{
			e.printStackTrace();
			fail(e.getMessage());
		}
	}
	
	@SuppressWarnings("unchecked")
	@Test
	public void testMerge1(){
		try	{
			invokeMethod("put","visitors/12",new Pair[]{},"",new Pair[]{},"Merge1","2.4");
			
		}
		catch (Throwable e)	{
			e.printStackTrace();
			fail(e.getMessage());
		}
	}


	@SuppressWarnings("unchecked")
	@Test
	public void testMerge2(){
		try	{
			invokeMethod("delete","users/12/cart/13/2.33",new Pair[]{},"",new Pair[]{},"Merge2","1562.33");
			
		}
		catch (Throwable e)	{
			e.printStackTrace();
			fail(e.getMessage());
		}
	}
    @SuppressWarnings("unchecked")
    @Test
    public void testHeaders1(){
        try	{
            invokeMethod("delete","users/12",new Pair[]{},"",new Pair[]{new Pair<String>("productID","12")},"Headers1","1440.0");

        }
        catch (Throwable e)	{
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
    @SuppressWarnings("unchecked")
    @Test
    public void testHeaders2(){
        try	{
            invokeMethod("delete","users/44/4",new Pair[]{},"",new Pair[]{new Pair<String>("a","12"),new Pair<String>("b","13")},"Headers1","44a: 12\nb: 13\n");

        }
        catch (Throwable e)	{
            e.printStackTrace();
            fail(e.getMessage());
        }
    }
	
}