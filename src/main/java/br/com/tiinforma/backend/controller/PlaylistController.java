package br.com.tiinforma.backend.controller;



import br.com.tiinforma.backend.controller.aws.StorageController;
import br.com.tiinforma.backend.domain.criador.Criador;
import br.com.tiinforma.backend.domain.criador.CriadorResponseDto;
import br.com.tiinforma.backend.domain.embeddedPk.PlaylistVideoId;
import br.com.tiinforma.backend.domain.enums.Funcao;
import br.com.tiinforma.backend.domain.enums.Visibilidade;
import br.com.tiinforma.backend.domain.playlist.Playlist;
import br.com.tiinforma.backend.domain.playlist.PlaylistAddVideosDto;
import br.com.tiinforma.backend.domain.playlist.PlaylistCreateDto;
import br.com.tiinforma.backend.domain.playlist.PlaylistResponseDto;

import br.com.tiinforma.backend.domain.playlistVideo.PlaylistVideo;
import br.com.tiinforma.backend.domain.playlistVideo.PlaylistVideoResponseDto;
import br.com.tiinforma.backend.domain.userDetails.UserDetailsImpl;
import br.com.tiinforma.backend.domain.usuario.Usuario;
import br.com.tiinforma.backend.domain.video.Video;
import br.com.tiinforma.backend.exceptions.ResourceNotFoundException;
import br.com.tiinforma.backend.repositories.*;
import br.com.tiinforma.backend.services.implementations.PlaylistPaginacaoService;
import br.com.tiinforma.backend.services.interfaces.PlaylistService;
import lombok.RequiredArgsConstructor;
import org.modelmapper.ModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/playlists")
@RequiredArgsConstructor
public class PlaylistController {

    private static final Logger log = LoggerFactory.getLogger(StorageController.class);

    @Autowired
    private PlaylistService playlistService;

    @Autowired
    private PlaylistPaginacaoService playlistPaginacaoService;

    @Autowired
    private UsuarioRepository usuarioRepository;

    @Autowired
    private PlaylistRepository playlistRepository;

    @Autowired
    private VideoRepository videoRepository;

    @Autowired
    private PlaylistVideoRepository playlistVideoRepository;

    @Autowired
    private CriadorRepository criadorRepository;

    @Autowired
    private ModelMapper modelMapper;


    @PostMapping("/criar")
    public ResponseEntity<?> criarPlaylist(
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetailsImpl userDetails
    ) {
        String nome = body.get("nome");
        String visibilidadeStr = body.get("visibilidade");
        String criadorIdStr = body.get("criadorId");

        if (nome == null || visibilidadeStr == null) {
            return ResponseEntity.badRequest().body("Nome e visibilidade são obrigatórios.");
        }

        Visibilidade visibilidade = Visibilidade.valueOf(visibilidadeStr);
        Usuario usuario = usuarioRepository.findById(userDetails.getId()).orElse(null);

        if (usuario == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Usuário não encontrado.");
        }

        Playlist playlist = Playlist.builder()
                .nome(nome)
                .visibilidade(visibilidade)
                .usuario(usuario)
                .build();

        if (criadorIdStr != null && !criadorIdStr.isEmpty()) {
            try {
                Long criadorId = Long.parseLong(criadorIdStr);
                Criador criador = criadorRepository.findById(criadorId).orElse(null);

                if (criador != null) {
                    playlist.setCriador(criador);
                }
            } catch (NumberFormatException e) {
            }
        }

        playlistRepository.save(playlist);

        PlaylistCreateDto dto = new PlaylistCreateDto(
                playlist.getId(),
                usuario.getId(),
                playlist.getCriador() != null ? playlist.getCriador().getId() : null,
                playlist.getNome(),
                playlist.getVisibilidade(),
                List.of()
        );

        return ResponseEntity.ok(dto);
    }

    @GetMapping
    public ResponseEntity<List<PlaylistResponseDto>> findAll() {
        return ResponseEntity.ok(playlistService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlaylistResponseDto> findById(@PathVariable Long id) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist não encontrada"));

        List<PlaylistVideoResponseDto> videos = playlist.getPlaylistVideos().stream()
                .sorted(Comparator.comparing(PlaylistVideo::getPosicaoVideo))
                .map(pv -> {
                    Video video = pv.getVideo();
                    return new PlaylistVideoResponseDto(
                            video.getId(),
                            video.getTitulo(),
                            video.getKey(),
                            video.getThumbnail(),
                            video.getDescricao(),
                            pv.getPosicaoVideo(),
                            pv.getDataAdicao(),
                            video.getDataPublicacao(),
                            video.getAvaliacaoMedia(),
                            video.getCriador() != null ?
                                    new CriadorResponseDto(
                                            video.getCriador().getId(),
                                            video.getCriador().getNome(),
                                            video.getCriador().getEmail(),
                                            video.getCriador().getFormacao(),
                                            video.getCriador().getFuncao(),
                                            video.getCriador().getTotalInscritos(),
                                            video.getCriador().getFotoUrl()
                                    ) : null
                    );
                })
                .collect(Collectors.toList());

        PlaylistResponseDto dto = new PlaylistResponseDto(
                playlist.getId(),
                playlist.getUsuario().getId(),
                playlist.getCriador() != null ? playlist.getCriador().getId() : null,
                playlist.getNome(),
                playlist.getVisibilidade(),
                "usuario",
                videos
        );

        return ResponseEntity.ok(dto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlaylistResponseDto> update(@PathVariable Long id, @RequestBody PlaylistCreateDto dto) {
        return ResponseEntity.ok(playlistService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id,
                                       @AuthenticationPrincipal UserDetailsImpl userDetails) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist não encontrada"));

        if (!playlist.getUsuario().getId().equals(userDetails.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            playlistVideoRepository.deleteAll(playlist.getPlaylistVideos());
            playlistRepository.flush();

            playlistRepository.delete(playlist);

            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Erro ao deletar playlist", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/search")
    public Page<PlaylistResponseDto> searchByName(@RequestParam String nome, Pageable pageable) {
        return playlistPaginacaoService.findByNome(nome, pageable);
    }

    @GetMapping("/minhas-playlists")
    public ResponseEntity<?> listarMinhasPlaylists(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        Usuario usuario = usuarioRepository.findById(userDetails.getId()).orElse(null);
        if (usuario == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Usuário não encontrado.");
        }
        List<Playlist> playlists = playlistRepository.findByUsuario(usuario);
        List<PlaylistResponseDto> dtos = playlists.stream().map(playlist -> {
            List<PlaylistVideoResponseDto> videoDtos = playlist.getPlaylistVideos().stream()
                    .map(pv -> {
                        Video video = pv.getVideo();
                        return new PlaylistVideoResponseDto(
                                video.getId(),
                                video.getTitulo(),
                                video.getKey(),
                                video.getThumbnail(),
                                video.getDescricao(),
                                pv.getPosicaoVideo(),
                                pv.getDataAdicao(),
                                video.getDataPublicacao(),
                                video.getAvaliacaoMedia(),
                                video.getCriador() != null ?
                                        new CriadorResponseDto(
                                                video.getCriador().getId(),
                                                video.getCriador().getNome(),
                                                video.getCriador().getEmail(),
                                                video.getCriador().getFormacao(),
                                                video.getCriador().getFuncao(),
                                                video.getCriador().getTotalInscritos(),
                                                video.getCriador().getFotoUrl()
                                        ) : null
                        );
                    })
                    .collect(Collectors.toList());

            return new PlaylistResponseDto(
                    playlist.getId(),
                    usuario.getId(),
                    playlist.getCriador() != null ? playlist.getCriador().getId() : null,
                    playlist.getNome(),
                    playlist.getVisibilidade(),
                    "usuario",
                    videoDtos
            );
        }).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/{playlistId}/adicionar-video")
    public ResponseEntity<?> adicionarVideoNaPlaylist(
            @PathVariable Long playlistId,
            @RequestParam Long videoId,
            @AuthenticationPrincipal UserDetailsImpl userDetails
    ) {
        Optional<Playlist> playlistOpt = playlistRepository.findById(playlistId);
        if (playlistOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Playlist não encontrada.");
        }
        Playlist playlist = playlistOpt.get();

        if (!playlist.getUsuario().getId().equals(userDetails.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Você não tem permissão para modificar esta playlist.");
        }

        Optional<Video> videoOptional = videoRepository.findById(videoId);
        if (videoOptional.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Vídeo não encontrado.");
        }
        Video videoParaAdicionar = videoOptional.get();

        boolean videoJaExiste = playlist.getPlaylistVideos().stream()
                .anyMatch(pv -> pv.getVideo().getId().equals(videoId));

        if (videoJaExiste) {
            return ResponseEntity.ok("O vídeo já está na playlist.");
        }

        int proximaPosicao = playlist.getPlaylistVideos().stream()
                .mapToInt(PlaylistVideo::getPosicaoVideo)
                .max()
                .orElse(0) + 1;

        PlaylistVideo playlistVideo = PlaylistVideo.builder()
                .id(new PlaylistVideoId(playlistId, videoId))
                .playlist(playlist)
                .video(videoParaAdicionar)
                .posicaoVideo(proximaPosicao)
                .dataAdicao(java.time.LocalDate.now())
                .build();

        playlistVideoRepository.save(playlistVideo);
        playlist.getPlaylistVideos().add(playlistVideo);

        PlaylistResponseDto responseDto = new PlaylistResponseDto(
                playlist.getId(),
                playlist.getUsuario().getId(),
                playlist.getCriador() != null ? playlist.getCriador().getId() : null,
                playlist.getNome(),
                playlist.getVisibilidade(),
                "usuario",
                playlist.getPlaylistVideos().stream()
                        .map(pv -> {
                            Video v = pv.getVideo(); // Variável renomeada
                            return new PlaylistVideoResponseDto(
                                    v.getId(),
                                    v.getTitulo(),
                                    v.getKey(),
                                    v.getThumbnail(),
                                    v.getDescricao(),
                                    pv.getPosicaoVideo(),
                                    pv.getDataAdicao(),
                                    v.getDataPublicacao(),
                                    v.getAvaliacaoMedia(),
                                    v.getCriador() != null ?
                                            new CriadorResponseDto(
                                                    v.getCriador().getId(),
                                                    v.getCriador().getNome(),
                                                    v.getCriador().getEmail(),
                                                    v.getCriador().getFormacao(),
                                                    v.getCriador().getFuncao(),
                                                    v.getCriador().getTotalInscritos(),
                                                    v.getCriador().getFotoUrl()
                                            ) : null
                            );
                        })
                        .collect(Collectors.toList())
        );

        return ResponseEntity.ok(responseDto);
    }


    @PatchMapping("/{id}/visibilidade")
    public ResponseEntity<PlaylistResponseDto> updateVisibilidade(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal UserDetailsImpl userDetails
    ) {
        Playlist playlist = playlistRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Playlist não encontrada"));

        if (!playlist.getUsuario().getId().equals(userDetails.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String visibilidadeStr = body.get("visibilidade");
        if (visibilidadeStr == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            Visibilidade visibilidade = Visibilidade.valueOf(visibilidadeStr);
            playlist.setVisibilidade(visibilidade);
            playlistRepository.save(playlist);

            return ResponseEntity.ok(new PlaylistResponseDto(
                    playlist.getId(),
                    playlist.getUsuario().getId(),
                    playlist.getCriador() != null ? playlist.getCriador().getId() : null,
                    playlist.getNome(),
                    playlist.getVisibilidade(),
                    "usuario",
                    playlist.getPlaylistVideos().stream()
                            .map(pv -> {
                                Video video = pv.getVideo();
                                return new PlaylistVideoResponseDto(
                                        video.getId(),
                                        video.getTitulo(),
                                        video.getKey(),
                                        video.getThumbnail(),
                                        video.getDescricao(),
                                        pv.getPosicaoVideo(),
                                        pv.getDataAdicao(),
                                        video.getDataPublicacao(),
                                        video.getAvaliacaoMedia(),
                                        video.getCriador() != null ?
                                                new CriadorResponseDto(
                                                        video.getCriador().getId(),
                                                        video.getCriador().getNome(),
                                                        video.getCriador().getEmail(),
                                                        video.getCriador().getFormacao(),
                                                        video.getCriador().getFuncao(),
                                                        video.getCriador().getTotalInscritos(),
                                                        video.getCriador().getFotoUrl()
                                                ) : null
                                );
                            })
                            .collect(Collectors.toList())
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/usuario/{userId}")
    public ResponseEntity<List<PlaylistResponseDto>> getPlaylistsByUser(@PathVariable Long userId) {
        Optional<Usuario> usuarioOptional = usuarioRepository.findById(userId);

        if (usuarioOptional.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Usuario usuario = usuarioOptional.get();

        List<Playlist> playlistsDoUsuario = playlistRepository.findByUsuarioId(userId);

        List<Playlist> playlistsDoCriador = new ArrayList<>();

        Optional<Criador> criadorOptional = criadorRepository.findByUsuarioId(userId);
        if (criadorOptional.isPresent()) {
            playlistsDoCriador = playlistRepository.findByCriadorId(criadorOptional.get().getId());
        }

        Set<Playlist> todasPlaylists = new HashSet<>();
        todasPlaylists.addAll(playlistsDoUsuario);
        todasPlaylists.addAll(playlistsDoCriador);

        List<PlaylistResponseDto> response = todasPlaylists.stream()
                .map(playlist -> {
                    String donoDaPlaylist = playlist.getUsuario().getId().equals(userId) ?
                            "usuario" : "criador";

                    List<PlaylistVideoResponseDto> videos = playlist.getPlaylistVideos().stream()
                            .sorted(Comparator.comparing(PlaylistVideo::getPosicaoVideo))
                            .map(this::mapToPlaylistVideoDto)
                            .collect(Collectors.toList());

                    return new PlaylistResponseDto(
                            playlist.getId(),
                            playlist.getUsuario().getId(),
                            playlist.getCriador() != null ? playlist.getCriador().getId() : null,
                            playlist.getNome(),
                            playlist.getVisibilidade(),
                            donoDaPlaylist,
                            videos
                    );
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    private PlaylistVideoResponseDto mapToPlaylistVideoDto(PlaylistVideo pv) {
        Video video = pv.getVideo();
        return new PlaylistVideoResponseDto(
                video.getId(),
                video.getTitulo(),
                video.getKey(),
                video.getThumbnail(),
                video.getDescricao(),
                pv.getPosicaoVideo(),
                pv.getDataAdicao(),
                video.getDataPublicacao(),
                video.getAvaliacaoMedia(),
                video.getCriador() != null ?
                        new CriadorResponseDto(
                                video.getCriador().getId(),
                                video.getCriador().getNome(),
                                video.getCriador().getEmail(),
                                video.getCriador().getFormacao(),
                                video.getCriador().getFuncao(),
                                video.getCriador().getTotalInscritos(),
                                video.getCriador().getFotoUrl()
                        ) : null
        );
    }
}
