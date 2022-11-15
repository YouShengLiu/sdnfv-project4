package nctu.winlab.unicastdhcp;
import org.onosproject.core.ApplicationId;
import org.onosproject.net.config.Config;

public class DhcpConfig extends Config<ApplicationId> {
  public static final String DhcpServerLocation = "serverLocation";

    @Override
    public boolean isValid() {
      return hasFields(DhcpServerLocation);
    }
    
    public String getServerLocation() {
        return get(DhcpServerLocation, null);
      }
}
