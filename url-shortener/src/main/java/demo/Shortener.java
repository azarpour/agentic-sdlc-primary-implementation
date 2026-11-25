package demo;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@SpringBootApplication
@RestController
public class Shortener {
    private final JdbcTemplate db;
    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();
    public Shortener(JdbcTemplate db,StringRedisTemplate redis){this.db=db;this.redis=redis;}
    public static void main(String[] args){SpringApplication.run(Shortener.class,args);}
    record Create(String url,String alias,Instant expiresAt){}
    static URI validate(String url){
        try { URI u=URI.create(url); if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||url.length()>4096) throw new IllegalArgumentException(); return u; }
        catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid URL");}
    }
    @PostMapping("/api/v1/urls")
    public ResponseEntity<Map<String,String>> create(@RequestBody Create input){
        validate(input.url());
        if(input.alias()!=null&&!input.alias().matches("[A-Za-z0-9_-]{3,32}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid alias");
        if(input.expiresAt()!=null&&!input.expiresAt().isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid expiration");
        for(int i=0;i<5;i++){
            String code=input.alias()!=null?input.alias():Base64.getUrlEncoder().withoutPadding().encodeToString(random.generateSeed(9));
            try {db.update("insert into links(code,url,expires_at) values (?,?,?)",code,input.url(),input.expiresAt()==null?null:Timestamp.from(input.expiresAt())); return ResponseEntity.status(201).body(Map.of("code",code,"shortPath","/"+code));}
            catch(DuplicateKeyException e){if(input.alias()!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Alias already exists");}
        } throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Code allocation exhausted");
    }
    @GetMapping("/{code:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<Void> redirect(@PathVariable String code){
        var urls=db.query("update links set clicks=clicks+1 where code=? and active and (expires_at is null or expires_at>now()) returning url",(r,n)->r.getString(1),code);
        if(urls.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return ResponseEntity.status(302).location(URI.create(urls.getFirst())).cacheControl(CacheControl.noStore()).build();
    }
    @GetMapping("/api/v1/urls/{code}/analytics")
    public Map<String,Object> analytics(@PathVariable String code){var rows=db.queryForList("select code,clicks from links where code=?",code);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND);return rows.getFirst();}
}
