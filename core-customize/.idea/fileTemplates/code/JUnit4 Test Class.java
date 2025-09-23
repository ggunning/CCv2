import static org.junit.Assert.*;
import static org.assertj.core.api.Assertions.*;

import org.mockito.*;
import de.hybris.bootstrap.annotations.UnitTest;
import org.mockito.MockitoAnnotations;
import org.mockito.InjectMocks;
import org.mockito.Mock;

#parse("File Header.java")

#set($CLASS_NAME_VAR = $CLASS_NAME.substring($CLASS_NAME.lastIndexOf('.')))
#set($CLASS_NAME_VAR = ${StringUtils.removeAndHump($CLASS_NAME_VAR, ".")})
#set($CLASS_NAME_VAR = $CLASS_NAME_VAR.substring(0,1).toLowerCase() + $CLASS_NAME_VAR.substring(1))

@UnitTest
public class ${NAME} {

  @InjectMocks
  private ${CLASS_NAME} ${CLASS_NAME_VAR};
  
  @Mock
  private Object mock;
  ${BODY}
}