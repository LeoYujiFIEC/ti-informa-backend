package br.com.tiinforma.backend.controller.aws;

import br.com.tiinforma.backend.domain.criador.Criador;
import br.com.tiinforma.backend.domain.enums.Funcao;
import br.com.tiinforma.backend.domain.userDetails.UserDetailsImpl;
import br.com.tiinforma.backend.domain.usuario.Usuario;
import br.com.tiinforma.backend.domain.video.Video;
import br.com.tiinforma.backend.domain.video.VideoResponseDto;
import br.com.tiinforma.backend.domain.video.VideoUploadDTO;
import br.com.tiinforma.backend.exceptions.ResourceNotFoundException;
import br.com.tiinforma.backend.repositories.CriadorRepository;
import br.com.tiinforma.backend.repositories.UsuarioRepository;
import br.com.tiinforma.backend.repositories.VideoRepository;
import br.com.tiinforma.backend.services.aws.StorageService;
import br.com.tiinforma.backend.services.interfaces.VideoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/file")
public class StorageController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    @Autowired
    private StorageService storageService;

    @Autowired
    private CriadorRepository criadorRepository;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VideoService videoService;

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam("thumbnail") MultipartFile thumbnail,
            @RequestParam("titulo") String titulo,
            @RequestParam("descricao") String descricao,
            @RequestParam(value = "categoria", required = false) String categoria,
            @RequestParam(value = "palavra_chave", required = false) String palavra_chave,
            @AuthenticationPrincipal UserDetailsImpl userDetails
    ) {
        log.info("Recebida requisição de upload de vídeo para o usuário: {}", userDetails.getUsername());
        log.info("Tamanho do thumbnail: {}", thumbnail.getSize());

        List<String> palavraChave = Collections.emptyList();
        try {
            if (palavra_chave != null) {
                palavraChave = objectMapper.readValue(palavra_chave, new TypeReference<List<String>>() {});
            }

            Criador criador = criadorRepository.findByEmail(userDetails.getUsername())
                    .orElseThrow(() -> new RuntimeException("Criador não encontrado"));

            VideoUploadDTO dto = new VideoUploadDTO(
                    file,
                    thumbnail,
                    titulo,
                    descricao,
                    categoria,
                    LocalDate.now(),
                    palavraChave
            );

            String response = String.valueOf(storageService.uploadFile(
                    dto.getFile(),
                    dto.getThumbnail(),
                    dto.getTitulo(),
                    dto.getDescricao(),
                    dto.getCategoria(),
                    dto.getDataCadastro(),
                    String.join(",", dto.getPalavraChave()),
                    criador
            ));

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            log.error("Erro durante o upload do vídeo: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Erro no upload do vídeo: " + e.getMessage());
        }
    }

    @GetMapping("/meus-videos")
    @Transactional
    public ResponseEntity<?> listarMeusVideos(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        try {
            Criador criador = criadorRepository.findByEmail(userDetails.getUsername())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Criador não encontrado"));
            List<Video> videos = videoRepository.findByCriadorId(criador.getId());
            return ResponseEntity.ok(videos);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Erro ao buscar vídeos");
        }
    }


    @PostMapping("foto/{tipo}/{id}")
    public ResponseEntity<String> uploadFoto(
            @PathVariable String tipo,
            @PathVariable Long id,
            @RequestParam MultipartFile file
    ){
        String response;

        switch (tipo.toLowerCase()) {
            case "criador":
                response = storageService.uploadFoto(file, id, criadorRepository);
                break;
            case "usuario":
                response = storageService.uploadFoto(file, id, usuarioRepository);
                break;
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo inválido");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    @GetMapping("/download/{fileName}")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable String fileName) {
        byte[] data = storageService.downloadFile(fileName);
        ByteArrayResource resource = new ByteArrayResource(data);
        return ResponseEntity
                .ok()
                .contentLength(data.length)
                .header("Content-Type", "application/octet-stream")
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .body(resource);
    }

    @DeleteMapping("/delete/{fileName}")
    public ResponseEntity<String> deleteFile(
            @PathVariable String fileName,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        String mensagem = storageService.deleteFile(fileName, userDetails.getUsername());
        return ResponseEntity.ok(mensagem);
    }

    @GetMapping("/videos-recomendados")
    public ResponseEntity<List<VideoResponseDto>> getRecommendedVideos(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Usuario> usuarioOptional = usuarioRepository.findByEmail(userDetails.getUsername());
        if (usuarioOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Usuario usuario = usuarioOptional.get();
        List<Video> recommendedVideos = new ArrayList<>();

        String interessesRaw = usuario.getInteresses();
        List<String> interessesList = null;

        if (interessesRaw != null && !interessesRaw.trim().isEmpty()) {
            interessesList = Arrays.asList(interessesRaw.split(","))
                    .stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }

        if (interessesList != null && !interessesList.isEmpty()) {
            recommendedVideos.addAll(videoService.buscarVideosRecomendados(interessesList));
        }

        List<Video> popularVideos = videoService.buscarVideosPopulares();
        recommendedVideos.addAll(popularVideos);

        List<Video> uniqueVideos = recommendedVideos.stream()
                .distinct()
                .collect(Collectors.toList());

        List<VideoResponseDto> dtos = uniqueVideos.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }


    @PostMapping("/{videoId}/visualizacao")
    public ResponseEntity<Void> incrementVideoViews(@PathVariable Long videoId) {
        boolean success = videoService.incrementarVisualizacao(videoId);
        if (success) {
            return ResponseEntity.noContent().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    private VideoResponseDto convertToDto(Video video) {
        List<String> palavrasChaveList = video.getPalavraChave() != null && !video.getPalavraChave().isEmpty() ?
                Arrays.asList(video.getPalavraChave().split(",")).stream().map(String::trim).collect(Collectors.toList()) :
                List.of();

        return new VideoResponseDto(
                video.getId(),
                video.getTitulo(),
                video.getThumbnail(),
                video.getDescricao(),
                video.getCategoria(),
                video.getDataPublicacao(),
                palavrasChaveList,
                video.getKey(),
                video.getVisualizacoes()
        );
    }

    @GetMapping("/{videoId}/visualizacoes")
    public ResponseEntity<Long> getVideoViews(@PathVariable Long videoId) {
        Optional<Video> videoOptional = videoRepository.findById(videoId);
        if (videoOptional.isPresent()) {
            return ResponseEntity.ok(videoOptional.get().getVisualizacoes());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/videos-populares")
    public ResponseEntity<List<VideoResponseDto>> getPopularVideos() {
        List<Video> popularVideos = videoService.buscarVideosPopulares();
        List<VideoResponseDto> dtos = popularVideos.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }
}
