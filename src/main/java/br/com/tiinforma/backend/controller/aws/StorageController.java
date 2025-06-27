package br.com.tiinforma.backend.controller.aws;

import br.com.tiinforma.backend.domain.criador.Criador;
import br.com.tiinforma.backend.domain.criador.CriadorResponseDto;
import br.com.tiinforma.backend.domain.enums.Funcao;
import br.com.tiinforma.backend.domain.userDetails.UserDetailsImpl;
import br.com.tiinforma.backend.domain.usuario.Usuario;
import br.com.tiinforma.backend.domain.video.Video;
import br.com.tiinforma.backend.domain.video.VideoResponseDto;
import br.com.tiinforma.backend.domain.video.VideoUploadDTO;
import br.com.tiinforma.backend.exceptions.ResourceNotFoundException;
import br.com.tiinforma.backend.repositories.CriadorRepository;
import br.com.tiinforma.backend.repositories.PlaylistVideoRepository;
import br.com.tiinforma.backend.repositories.UsuarioRepository;
import br.com.tiinforma.backend.repositories.VideoRepository;
import br.com.tiinforma.backend.services.aws.StorageService;
import br.com.tiinforma.backend.services.implementations.CriadorImpl;
import br.com.tiinforma.backend.services.implementations.UsuarioImpl;
import br.com.tiinforma.backend.services.interfaces.CriadorService;
import br.com.tiinforma.backend.services.interfaces.UsuarioService;
import br.com.tiinforma.backend.services.interfaces.VideoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.StorageException;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataAccessException;
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
    private PlaylistVideoRepository playlistVideoRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VideoService videoService;

    @Autowired
    private ModelMapper modelMapper;

    @Autowired
    private UsuarioImpl usuarioImpl;

    @Autowired
    private CriadorImpl criadorImpl;

    @PostMapping("/upload")
    public ResponseEntity<String> uploadVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam("thumbnail") MultipartFile thumbnail,
            @RequestParam("titulo") String titulo,
            @RequestParam("descricao") String descricao,
            @RequestParam(value = "categoria", required = false) String categoria,
            @RequestParam(value = "palavra_chave", required = false) String palavra_chave,
            @AuthenticationPrincipal UserDetailsImpl userDetails
    ) {
        log.info("Recebida requisição de upload de vídeo para o usuário: {}", userDetails.getUsername());

        try {
            List<String> palavraChave = Collections.emptyList();
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

            Video video = storageService.uploadFile(
                    dto.getFile(),
                    dto.getThumbnail(),
                    dto.getTitulo(),
                    dto.getDescricao(),
                    dto.getCategoria(),
                    dto.getDataCadastro(),
                    String.join(",", dto.getPalavraChave()),
                    criador
            );

            String videoUrl = "/video/" + video.getId();
            video.setUrl(videoUrl);
            videoRepository.save(video);

            VideoResponseDto responseDto = convertToResponseDto(video);

            return ResponseEntity.status(HttpStatus.CREATED).body("Vídeo enviado com sucesso!");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Erro no upload");
        }
    }

    private VideoResponseDto convertToResponseDto(Video video) {
        List<String> palavrasChaveList = video.getPalavraChave() != null && !video.getPalavraChave().isEmpty() ?
                Arrays.asList(video.getPalavraChave().split(",")) :
                List.of();

        return new VideoResponseDto(
                video.getId(),
                video.getTitulo(),
                video.getThumbnail(),
                video.getDescricao(),
                video.getUrl(),
                video.getCategoria(),
                video.getDataPublicacao(),
                palavrasChaveList,
                video.getKey(),
                video.getVisualizacoes(),
                video.getAvaliacaoMedia(),
                new CriadorResponseDto(
                        video.getCriador().getId(),
                        video.getCriador().getNome(),
                        video.getCriador().getEmail(),
                        video.getCriador().getFormacao(),
                        video.getCriador().getFuncao(),
                        video.getCriador().getTotalInscritos(),
                        video.getCriador().getFotoUrl()
                )
        );
    }

    @GetMapping("/meus-videos")
    @Transactional
    public ResponseEntity<?> listarMeusVideos(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        try {
            Criador criador = criadorRepository.findByEmail(userDetails.getUsername())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Criador não encontrado"));

            List<Video> videos = videoRepository.findByCriadorId(criador.getId());

            List<VideoResponseDto> response = videos.stream()
                    .map(this::convertToResponseDto)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
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

    @DeleteMapping("/delete/{videoId}")
    public ResponseEntity<String> deleteVideo(
            @PathVariable Long videoId,
            @AuthenticationPrincipal UserDetailsImpl userDetails
    ) {
        try {
            Video video = videoRepository.findById(videoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Vídeo não encontrado"));

            if (!video.getCriador().getEmail().equals(userDetails.getUsername())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Você não tem permissão para excluir este vídeo");
            }

            videoService.deletarAvaliacoesDoVideo(videoId);

            playlistVideoRepository.deleteAll(video.getPlaylistVideos());
            videoRepository.flush();

            storageService.deleteFile(video.getKey(), userDetails.getUsername());

            if (video.getThumbnail() != null && !video.getThumbnail().isEmpty()) {
                storageService.deleteFile(video.getThumbnail(), userDetails.getUsername());
            }

            videoRepository.delete(video);

            return ResponseEntity.ok("{\"message\": \"Vídeo excluído com sucesso\"}");
        } catch (Exception e) {
            log.error("Erro ao deletar vídeo", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("{\"error\": \"Erro ao excluir vídeo: " + e.getMessage() + "\"}");
        }
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
                video.getUrl(),
                video.getCategoria(),
                video.getDataPublicacao(),
                palavrasChaveList,
                video.getKey(),
                video.getVisualizacoes(),
                video.getAvaliacaoMedia(),
                new CriadorResponseDto(
                        video.getCriador().getId(),
                        video.getCriador().getNome(),
                        video.getCriador().getEmail(),
                        video.getCriador().getFormacao(),
                        video.getCriador().getFuncao(),
                        video.getCriador().getTotalInscritos(),
                        video.getCriador().getFotoUrl()
                )
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

    @GetMapping("/{videoId}/avaliacao-media")
    public ResponseEntity<Double> getAvaliacaoMedia(@PathVariable Long videoId) {
        Double media = videoService.calcularMediaAvaliacoes(videoId);

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ResourceNotFoundException("Vídeo não encontrado"));
        video.setAvaliacaoMedia(media);
        videoRepository.save(video);

        return ResponseEntity.ok(media);
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<VideoResponseDto> getVideo(@PathVariable Long videoId) {
        Video video = videoService.buscarVideoPorId(videoId);

        Double media = videoService.calcularMediaAvaliacoes(videoId);
        video.setAvaliacaoMedia(media);
        videoRepository.save(video);

        VideoResponseDto responseDto = convertToResponseDto(video);
        return ResponseEntity.ok(responseDto);
    }

    @GetMapping("/videos-mais-avaliados/interesses")
    public ResponseEntity<List<VideoResponseDto>> getTopRatedVideosByInterests(@AuthenticationPrincipal UserDetailsImpl userDetails) {
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

        List<Video> topRatedVideos = recommendedVideos.stream()
                .filter(v -> v.getAvaliacaoMedia() != null && v.getAvaliacaoMedia() >= 3.5)
                .sorted((v1, v2) -> Double.compare(v2.getAvaliacaoMedia(), v1.getAvaliacaoMedia()))
                .collect(Collectors.toList());

        List<VideoResponseDto> dtos = topRatedVideos.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/videos-mais-avaliados/populares")
    public ResponseEntity<List<VideoResponseDto>> getTopRatedPopularVideos() {
        List<Video> popularVideos = videoService.buscarVideosPopulares();

        List<Video> topRatedVideos = popularVideos.stream()
                .filter(v -> v.getAvaliacaoMedia() != null && v.getAvaliacaoMedia() >= 3.5)
                .sorted((v1, v2) -> Double.compare(v2.getAvaliacaoMedia(), v1.getAvaliacaoMedia()))
                .collect(Collectors.toList());

        List<VideoResponseDto> dtos = topRatedVideos.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/videos-recentes/interesses")
    public ResponseEntity<List<VideoResponseDto>> getRecentVideosByInterests(@AuthenticationPrincipal UserDetailsImpl userDetails) {
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

        List<Video> recentVideos = recommendedVideos.stream()
                .sorted((v1, v2) -> v2.getDataPublicacao().compareTo(v1.getDataPublicacao()))
                .collect(Collectors.toList());

        List<VideoResponseDto> dtos = recentVideos.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/videos-recentes/populares")
    public ResponseEntity<List<VideoResponseDto>> getRecentPopularVideos() {
        List<Video> popularVideos = videoService.buscarVideosPopulares();

        List<Video> recentVideos = popularVideos.stream()
                .sorted((v1, v2) -> v2.getDataPublicacao().compareTo(v1.getDataPublicacao()))
                .collect(Collectors.toList());

        List<VideoResponseDto> dtos = recentVideos.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/criador/{criadorId}/videos")
    public ResponseEntity<List<VideoResponseDto>> getVideosByCriador(@PathVariable Long criadorId) {
        List<Video> videos = videoRepository.findByCriadorId(criadorId);
        List<VideoResponseDto> response = videos.stream()
                .map(video -> modelMapper.map(video, VideoResponseDto.class))
                .collect(Collectors.toList());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/foto-upload")
    public ResponseEntity<?> uploadFotoUsuario(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserDetailsImpl userDetails) {

        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("Arquivo não pode estar vazio");
            }

            if (!file.getContentType().startsWith("image/")) {
                return ResponseEntity.badRequest().body("Apenas arquivos de imagem são permitidos");
            }

            String fotoUrl = storageService.uploadFotoUsuario(file, userDetails.getUsername());

            usuarioImpl.atualizarFotoUrl(userDetails.getUsername(), fotoUrl);

            if (userDetails.getFuncao() == Funcao.CRIADOR) {
                criadorImpl.atualizarFotoUrl(userDetails.getUsername(), fotoUrl);
            }

            return ResponseEntity.ok().body(Map.of(
                    "fotoUrl", fotoUrl,
                    "message", "Upload da foto realizado com sucesso"
            ));

        } catch (StorageException e) {
            log.error("Erro no storage durante upload: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao armazenar a foto: " + e.getMessage());

        } catch (DataAccessException e) {
            log.error("Erro de banco de dados durante atualização: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao atualizar os registros: " + e.getMessage());

        } catch (Exception e) {
            log.error("Erro inesperado durante upload de foto: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao processar a foto: " + e.getMessage());
        }
    }

    @GetMapping("/foto-usuario")
    public ResponseEntity<?> getFotoUsuario(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        try {
            String fotoUrl = storageService.getFotoUsuario(userDetails.getUsername());
            if (fotoUrl == null || fotoUrl.isEmpty()) {
                return ResponseEntity.ok().body(Collections.singletonMap("fotoUrl", ""));
            }
            return ResponseEntity.ok().body(Collections.singletonMap("fotoUrl", fotoUrl));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Erro ao buscar foto do usuário: " + e.getMessage());
        }
    }

    @GetMapping("/buscar")
    public ResponseEntity<List<VideoResponseDto>> buscarVideosPorTitulo(@RequestParam String termo) {
        try {
            List<Video> videos = videoRepository.findByTituloContainingIgnoreCase(termo);

            List<VideoResponseDto> response = videos.stream()
                    .map(this::convertToResponseDto)
                    .collect(Collectors.toList());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Erro ao buscar vídeos por título", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

